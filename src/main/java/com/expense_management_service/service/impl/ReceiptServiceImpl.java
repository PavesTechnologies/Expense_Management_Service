package com.expense_management_service.service.impl;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.response.LateReceiptResponse;
import com.expense_management_service.dto.response.ReceiptResponse;
import com.expense_management_service.dto.response.ReceiptUrlResponse;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.entity.ReceiptOcr;
import com.expense_management_service.mapper.ReceiptMapper;
import com.expense_management_service.enums.DuplicateReceiptStatus;
import com.expense_management_service.enums.OcrStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.event.ReceiptUploadedEvent;
import com.expense_management_service.repository.ApprovalAssignmentRepository;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.ReceiptOcrRepository;
import com.expense_management_service.repository.ReceiptRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.RoleConstants;
import com.expense_management_service.service.DelegationService;
import com.expense_management_service.service.ReceiptDuplicateService;
import com.expense_management_service.service.ReceiptDuplicateService.DuplicateMatch;
import com.expense_management_service.service.ReceiptService;
import com.expense_management_service.storage.StorageException;
import com.expense_management_service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@link ReceiptService} implementation (EP03-S4). A receipt is created directly under its
 * {@code ExpenseReport} — no line item is required at upload time for the OCR flow ({@link #upload}).
 * There, OCR runs against the report-level receipt, and an {@code ExpenseLineItem} is only
 * created/linked later, when the employee confirms (see {@code ReceiptConfirmationService}).
 * <p>
 * For Manual Expense Entry, where the line item already exists before its receipt is attached,
 * {@link #uploadForLineItem} populates {@code Receipt.lineItem} immediately. Both paths share
 * {@link #uploadInternal} for validation, storage, and metadata persistence.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ReceiptServiceImpl implements ReceiptService {

    /**
     * WEBP is accepted for upload/storage/viewing like any other receipt image, but AWS Textract
     * itself does not accept WEBP as an input format for any of its APIs (JPEG, PNG, PDF, and
     * TIFF only) — a WEBP receipt's OCR attempt will fail with a clear, categorized
     * {@code TextractIntegrationException} (see {@code TextractServiceImpl}) rather than silently
     * producing a blank result. Employees can still enter such receipts manually.
     */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf", "image/png", "image/jpeg", "image/webp");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "png", "jpg", "jpeg", "webp");

    /** One expected byte run at a given offset within the file — a signature is one or more of these. */
    private record SignaturePart(int offset, byte[] magic) {
    }

    /**
     * File-signature ("magic bytes") of each allowed extension, checked against the actual
     * uploaded bytes — declared Content-Type and file extension are both attacker-controlled
     * (renaming a file changes both at once), so neither alone proves what the file actually is.
     * WEBP's signature is two separate runs ("RIFF" at offset 0, "WEBP" at offset 8) rather than
     * one contiguous prefix, hence a list of parts instead of a single byte array per extension.
     */
    private static final Map<String, List<SignaturePart>> SIGNATURES_BY_EXTENSION = Map.of(
            "pdf", List.of(new SignaturePart(0, new byte[] {0x25, 0x50, 0x44, 0x46})),
            "png", List.of(new SignaturePart(0, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})),
            "jpg", List.of(new SignaturePart(0, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})),
            "jpeg", List.of(new SignaturePart(0, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})),
            "webp", List.of(
                    new SignaturePart(0, new byte[] {0x52, 0x49, 0x46, 0x46}),
                    new SignaturePart(8, new byte[] {0x57, 0x45, 0x42, 0x50}))
    );

    private final ReceiptRepository receiptRepository;
    private final ExpenseReportRepository expenseReportRepository;
    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final StorageService storageService;
    private final CurrentUserService currentUserService;
    private final ReceiptMapper receiptMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ApprovalAssignmentRepository approvalAssignmentRepository;
    private final DelegationService delegationService;
    private final ReceiptDuplicateService receiptDuplicateService;
    private final ReceiptOcrRepository receiptOcrRepository;
    private final AuditLogRepository auditLogRepository;

    /** Post-approval states in which a receipt can still be attached to an expense without reopening its claim. */
    private static final Set<ReportStatus> LATE_ATTACHABLE = EnumSet.of(ReportStatus.APPROVED, ReportStatus.REIMBURSED, ReportStatus.CLOSED);
    /** Report states that are not a claim: a receipt filed there does not count as claimed. */
    private static final Set<ReportStatus> NOT_CLAIMED = EnumSet.of(ReportStatus.DRAFT, ReportStatus.REJECTED, ReportStatus.CANCELLED);
    private static final int MIN_REASON_LENGTH = 10;
    private static final BigDecimal LATE_AMOUNT_TOLERANCE = new BigDecimal("0.01");
    private static final Set<String> REVIEW_ROLES = Set.of(RoleConstants.FINANCE, RoleConstants.FINANCE_EXECUTIVE, RoleConstants.AP_EXECUTIVE);

    @Value("${receipt.max-file-size-bytes:10485760}")
    private long maxFileSizeBytes;

    @Value("${receipt.presigned-url-ttl-minutes:15}")
    private long presignedUrlTtlMinutes;

    @Override
    public ReceiptResponse upload(UUID reportId, MultipartFile file) {
        ExpenseReport report = findReport(reportId);
        return uploadInternal(report, null, file);
    }

    @Override
    public ReceiptResponse uploadForLineItem(UUID lineItemId, MultipartFile file) {
        ExpenseLineItem lineItem = findLineItem(lineItemId);
        return uploadInternal(lineItem.getReport(), lineItem, file);
    }

    /**
     * Shared upload path for both the report-level OCR flow ({@code lineItem == null}) and the
     * Manual Expense Entry line-item flow ({@code lineItem} already exists). Ownership/status
     * gating is always checked against the parent {@code report}, since a line item always
     * belongs to exactly one report.
     */
    private ReceiptResponse uploadInternal(ExpenseReport report, ExpenseLineItem lineItem, MultipartFile file) {
        assertOwnerOrAdmin(report);
        assertReportEditable(report);
        byte[] fileBytes = assertFileValid(file);

        String originalFileName = extractBaseFileName(file.getOriginalFilename());
        String storedFileName = UUID.randomUUID() + "-" + sanitizeForKey(originalFileName);
        String objectKey = "receipts/" + report.getEmployeeId() + "/" + report.getReportId()
                + (lineItem != null ? "/" + lineItem.getLineItemId() : "") + "/" + storedFileName;

        storageService.upload(objectKey, file);

        Receipt entity = Receipt.builder()
                .report(report)
                .lineItem(lineItem)
                .employeeId(report.getEmployeeId())
                .originalFileName(originalFileName)
                .storedFileName(storedFileName)
                .objectKey(objectKey)
                .contentType(file.getContentType())
                .fileSize((int) file.getSize())
                .uploadedBy(currentUserService.getCurrentUser().employeeId())
                .uploadedAt(LocalDateTime.now())
                .ocrStatus(OcrStatus.UPLOADED.name())
                .fileHash(computeFileHash(fileBytes))
                .build();

        try {
            // saveAndFlush (not save) so a constraint violation surfaces here, inside the
            // try/catch, rather than later at transaction-commit time — a deferred flush
            // failure would otherwise skip the compensating S3 delete below entirely.
            Receipt saved = receiptRepository.saveAndFlush(entity);
            log.info("[OCR] Receipt {} saved for report {} — publishing ReceiptUploadedEvent", saved.getReceiptId(), report.getReportId());
            applicationEventPublisher.publishEvent(new ReceiptUploadedEvent(saved.getReceiptId()));
            log.info("[OCR] ReceiptUploadedEvent published for receipt {}", saved.getReceiptId());
            return toResponseWithDuplicateCheck(saved);
        } catch (RuntimeException ex) {
            log.error("Metadata save failed after storage upload succeeded for report {} — deleting the now-orphaned file", report.getReportId(), ex);
            safeDeleteFromStorage(objectKey);
            throw ex;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReceiptResponse> getAllForReport(UUID reportId) {
        ExpenseReport report = findReport(reportId);
        assertViewable(report);
        return receiptRepository.findByReport_ReportId(reportId).stream().map(this::toResponseWithDuplicateCheck).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReceiptResponse> getAllForLineItem(UUID lineItemId) {
        ExpenseLineItem lineItem = findLineItem(lineItemId);
        assertViewable(lineItem.getReport());
        return receiptRepository.findByLineItem_LineItemId(lineItemId).stream().map(this::toResponseWithDuplicateCheck).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ReceiptResponse getById(UUID receiptId) {
        Receipt entity = findReceipt(receiptId);
        assertViewable(entity.getReport());
        return toResponseWithDuplicateCheck(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public ReceiptUrlResponse getViewUrl(UUID receiptId) {
        Receipt entity = findReceipt(receiptId);
        assertViewable(entity.getReport());
        Duration ttl = Duration.ofMinutes(presignedUrlTtlMinutes);
        String url = storageService.generateViewUrl(entity.getObjectKey(), ttl);
        log.info("Generated view URL for receipt {}", receiptId);
        return new ReceiptUrlResponse(url, LocalDateTime.now().plus(ttl));
    }

    @Override
    @Transactional(readOnly = true)
    public ReceiptUrlResponse getDownloadUrl(UUID receiptId) {
        Receipt entity = findReceipt(receiptId);
        assertViewable(entity.getReport());
        Duration ttl = Duration.ofMinutes(presignedUrlTtlMinutes);
        String url = storageService.generateDownloadUrl(entity.getObjectKey(), entity.getOriginalFileName(), ttl);
        log.info("Generated download URL for receipt {}", receiptId);
        return new ReceiptUrlResponse(url, LocalDateTime.now().plus(ttl));
    }

    @Override
    public void delete(UUID receiptId) {
        Receipt entity = findReceipt(receiptId);
        ExpenseReport report = entity.getReport();
        assertOwnerOrAdmin(report);
        assertReportEditable(report);

        // DB consistency wins over storage cleanliness: a failed delete from storage is
        // logged for later reconciliation, but the metadata row is still removed so the UI
        // never shows a "receipt" that XMS has already given up trying to clean up.
        safeDeleteFromStorage(entity.getObjectKey());
        receiptRepository.delete(entity);
        receiptRepository.flush();
        log.info("Deleted receipt {}", receiptId);
    }

    @Override
    public LateReceiptResponse attachLate(UUID lineItemId, MultipartFile file, LocalDate actualReceiptDate, String reason) {
        ExpenseLineItem lineItem = findLineItem(lineItemId);
        ExpenseReport report = lineItem.getReport();
        assertOwnerOrAdmin(report);
        if (!LATE_ATTACHABLE.contains(report.getReportStatus())) {
            throw new BusinessRuleViolationException("A late receipt can be attached only after the report is approved, reimbursed or closed. "
                    + "This report is " + report.getReportStatus() + ", so upload the receipt normally.");
        }
        if (actualReceiptDate == null) {
            throw new IllegalArgumentException("Enter the date printed on the receipt.");
        }
        if (actualReceiptDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("The receipt date cannot be in the future.");
        }
        String reasonText = requireReason(reason);
        byte[] fileBytes = assertFileValid(file);
        String fileHash = computeFileHash(fileBytes);
        assertNotAlreadyClaimed(fileHash, lineItemId);

        String originalFileName = extractBaseFileName(file.getOriginalFilename());
        String storedFileName = UUID.randomUUID() + "-" + sanitizeForKey(originalFileName);
        String objectKey = "receipts/" + report.getEmployeeId() + "/" + report.getReportId() + "/" + lineItemId + "/late/" + storedFileName;
        storageService.upload(objectKey, file);

        Receipt entity = Receipt.builder()
                .report(report)
                .lineItem(lineItem)
                .employeeId(report.getEmployeeId())
                .originalFileName(originalFileName)
                .storedFileName(storedFileName)
                .objectKey(objectKey)
                .contentType(file.getContentType())
                .fileSize((int) file.getSize())
                .uploadedBy(currentUserService.getCurrentUser().employeeId())
                .uploadedAt(LocalDateTime.now())
                .ocrStatus(OcrStatus.UPLOADED.name())
                .fileHash(fileHash)
                .attachmentKind(Receipt.ATTACHMENT_LATE)
                .actualReceiptDate(actualReceiptDate)
                .lateReason(reasonText)
                .verificationStatus(Receipt.VERIFICATION_PENDING)
                .build();
        try {
            Receipt saved = receiptRepository.saveAndFlush(entity);
            audit(saved, "LATE_RECEIPT_ATTACHED", "Expense " + lineItemId + " on report " + report.getReportNumber(), reasonText);
            // Same event as a normal upload, so OCR runs automatically on the late receipt too.
            applicationEventPublisher.publishEvent(new ReceiptUploadedEvent(saved.getReceiptId()));
            return toLateResponse(saved);
        } catch (RuntimeException ex) {
            log.error("Late receipt metadata save failed after storage upload for report {} — deleting the orphaned file", report.getReportId(), ex);
            safeDeleteFromStorage(objectKey);
            throw ex;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<LateReceiptResponse> getLateForReport(UUID reportId) {
        ExpenseReport report = findReport(reportId);
        assertViewable(report);
        return receiptRepository.findByReport_ReportId(reportId).stream()
                .filter(this::isLate)
                .map(this::toLateResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LateReceiptResponse> getPendingLateReceipts() {
        assertReviewer(null);
        return receiptRepository.findByAttachmentKindAndVerificationStatusOrderByUploadedAtAsc(
                        Receipt.ATTACHMENT_LATE, Receipt.VERIFICATION_PENDING).stream()
                .map(this::toLateResponse)
                .toList();
    }

    @Override
    public LateReceiptResponse verifyLate(UUID receiptId, String note) {
        Receipt receipt = findLateReceipt(receiptId);
        assertReviewer(receipt);
        assertPending(receipt);
        String noteText = trimmedOrNull(note);

        List<DuplicateMatch> blocking = receiptDuplicateService.findMatches(receipt).stream()
                .filter(DuplicateMatch::blocking)
                .toList();
        if (!blocking.isEmpty()) {
            throw new BusinessRuleViolationException(blocking.get(0).message() + " Reject this receipt instead.");
        }

        Optional<ReceiptOcr> ocr = receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receipt.getReceiptId())
                .filter(o -> o.getProcessingStatus() == OcrStatus.OCR_COMPLETED);
        if (noteText == null && ocr.isPresent() && totalDiffersFromClaim(ocr.get(), receipt.getLineItem())) {
            throw new BusinessRuleViolationException("The receipt total is " + ocr.get().getAmount().toPlainString()
                    + " but the claimed amount is " + receipt.getLineItem().getAmount().toPlainString()
                    + ". Add a note explaining the difference to verify it.");
        }

        receipt.setVerificationStatus(Receipt.VERIFICATION_VERIFIED);
        receipt.setVerifiedBy(currentUserService.getCurrentUser().employeeId());
        receipt.setVerifiedAt(LocalDateTime.now());
        receipt.setVerificationNote(noteText);
        receiptRepository.save(receipt);
        audit(receipt, "LATE_RECEIPT_VERIFIED", Receipt.VERIFICATION_VERIFIED, noteText);
        return toLateResponse(receipt);
    }

    @Override
    public LateReceiptResponse rejectLate(UUID receiptId, String note) {
        Receipt receipt = findLateReceipt(receiptId);
        assertReviewer(receipt);
        assertPending(receipt);
        String noteText = requireReason(note);

        receipt.setVerificationStatus(Receipt.VERIFICATION_REJECTED);
        receipt.setVerifiedBy(currentUserService.getCurrentUser().employeeId());
        receipt.setVerifiedAt(LocalDateTime.now());
        receipt.setVerificationNote(noteText);
        receiptRepository.save(receipt);
        audit(receipt, "LATE_RECEIPT_REJECTED", Receipt.VERIFICATION_REJECTED, noteText);
        return toLateResponse(receipt);
    }

    /** A file already claimed on this line, or on another report that is still a claim, cannot be attached again. */
    private void assertNotAlreadyClaimed(String fileHash, UUID lineItemId) {
        for (Receipt other : receiptRepository.findByFileHash(fileHash)) {
            ExpenseLineItem otherLine = other.getLineItem();
            if (otherLine == null) {
                continue;
            }
            if (otherLine.getLineItemId().equals(lineItemId)) {
                throw new BusinessRuleViolationException("This receipt is already attached to this expense.");
            }
            if (!NOT_CLAIMED.contains(other.getReport().getReportStatus())) {
                throw new BusinessRuleViolationException("This receipt has already been claimed on expense report "
                        + other.getReport().getReportNumber() + ". The same receipt can't be claimed twice.");
            }
        }
    }

    private boolean totalDiffersFromClaim(ReceiptOcr ocr, ExpenseLineItem line) {
        if (ocr.getAmount() == null || line.getAmount() == null) {
            return false;
        }
        if (ocr.getCurrencyCode() != null && line.getCurrency() != null
                && !ocr.getCurrencyCode().equalsIgnoreCase(line.getCurrency().getCurrencyCode())) {
            return false;
        }
        return ocr.getAmount().subtract(line.getAmount()).abs().compareTo(LATE_AMOUNT_TOLERANCE) > 0;
    }

    /** Finance/AP only, and never the person who uploaded the receipt or whose expense it is. */
    private void assertReviewer(Receipt receipt) {
        CurrentUser caller = currentUserService.getCurrentUser();
        boolean reviewer = caller.roles() != null && caller.roles().stream().anyMatch(REVIEW_ROLES::contains);
        if (!reviewer) {
            throw new AccessDeniedException("Only Finance or AP can review late receipts");
        }
        if (receipt != null && (caller.employeeId().equals(receipt.getUploadedBy())
                || caller.employeeId().equals(receipt.getEmployeeId()))) {
            throw new AccessDeniedException("You cannot review a receipt for your own expense");
        }
    }

    private void assertPending(Receipt receipt) {
        if (!Receipt.VERIFICATION_PENDING.equals(receipt.getVerificationStatus())) {
            throw new BusinessRuleViolationException("This receipt has already been "
                    + String.valueOf(receipt.getVerificationStatus()).toLowerCase(Locale.ROOT) + ".");
        }
    }

    private Receipt findLateReceipt(UUID receiptId) {
        Receipt receipt = findReceipt(receiptId);
        if (!isLate(receipt)) {
            throw new BusinessRuleViolationException("Only late receipts are reviewed here.");
        }
        return receipt;
    }

    private boolean isLate(Receipt receipt) {
        return Receipt.ATTACHMENT_LATE.equals(receipt.getAttachmentKind());
    }

    private LateReceiptResponse toLateResponse(Receipt receipt) {
        ExpenseLineItem line = receipt.getLineItem();
        ExpenseReport report = receipt.getReport();
        ReceiptDuplicateService.DuplicateSummary duplicate = receiptDuplicateService.summarize(receipt);
        DuplicateReceiptStatus status = duplicate != null ? duplicate.status() : DuplicateReceiptStatus.NONE;
        return new LateReceiptResponse(
                receipt.getReceiptId(),
                report.getReportId(),
                report.getReportNumber(),
                line != null ? line.getLineItemId() : null,
                receipt.getEmployeeId(),
                line != null ? line.getExpenseDate() : null,
                line != null ? line.getAmount() : null,
                receipt.getOriginalFileName(),
                receipt.getUploadedBy(),
                receipt.getUploadedAt(),
                receipt.getActualReceiptDate(),
                receipt.getLateReason(),
                receipt.getOcrStatus(),
                receipt.getVerificationStatus(),
                receipt.getVerifiedBy(),
                receipt.getVerifiedAt(),
                receipt.getVerificationNote(),
                status.name(),
                duplicate != null ? duplicate.message() : null);
    }

    private void audit(Receipt receipt, String action, String newValue, String reason) {
        auditLogRepository.save(AuditLog.builder()
                .entityName("Receipt")
                .entityId(receipt.getReceiptId())
                .action(action)
                .newValue(newValue)
                .reason(reason)
                .performedBy(currentUserService.getCurrentUser().employeeId())
                .performedAt(LocalDateTime.now())
                .build());
    }

    private static String requireReason(String value) {
        String trimmed = trimmedOrNull(value);
        if (trimmed == null || trimmed.length() < MIN_REASON_LENGTH) {
            throw new IllegalArgumentException("Give a reason of at least " + MIN_REASON_LENGTH + " characters.");
        }
        return trimmed;
    }

    private static String trimmedOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void safeDeleteFromStorage(String objectKey) {
        try {
            storageService.delete(objectKey);
        } catch (StorageException ex) {
            // Object key intentionally omitted from this log line — it is never logged
            // anywhere in this service. Recoverable from the Receipt row if reconciliation
            // is ever needed; it just won't be needed here, since the row is about to go too.
            log.error("Failed to delete a file from storage — it may now be orphaned and will need manual/cron cleanup", ex);
        }
    }

    /** Returns the file's full bytes once validation passes — reused by the caller to compute {@code fileHash} without reading the file a second time. */
    private byte[] assertFileValid(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file must not be empty");
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new IllegalArgumentException(
                    "Uploaded file exceeds the maximum allowed size of " + (maxFileSizeBytes / (1024 * 1024)) + "MB");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException(
                    "Unsupported file type: " + file.getContentType() + ". Allowed types: PDF, PNG, JPG, JPEG, WEBP");
        }
        String extension = extractExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                    "Unsupported file extension: ." + extension + ". Allowed: pdf, png, jpg, jpeg, webp");
        }
        return assertContentMatchesDeclaredType(file, extension);
    }

    /**
     * Verifies the file's actual bytes match every signature part expected for its extension.
     * Both the declared Content-Type and the file extension are attacker-controlled (renaming
     * a file changes both at once), so passing those two checks alone does not prove the file
     * actually is a PDF/PNG/JPEG/WEBP — only its content can. Returns those bytes so the caller
     * doesn't have to read the (potentially large) file from the wire a second time.
     */
    private byte[] assertContentMatchesDeclaredType(MultipartFile file, String extension) {
        List<SignaturePart> signatureParts = SIGNATURES_BY_EXTENSION.get(extension);
        byte[] header;
        try {
            header = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the uploaded file to verify its type");
        }
        boolean matches = signatureParts.stream().allMatch(part -> matchesAt(header, part));
        if (!matches) {
            throw new IllegalArgumentException(
                    "File content does not match its declared type (." + extension + ") — the file may be corrupted or mislabeled");
        }
        return header;
    }

    /** SHA-256 of the exact file bytes — used only for advisory exact-file-reuse detection (see {@link #uploadInternal}), never for security/integrity purposes. */
    private String computeFileHash(byte[] fileBytes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(fileBytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available on this JVM", e);
        }
    }

    /**
     * Two duplicate signals: {@code possibleDuplicateFileReuse} is the advisory exact-file match
     * against any receipt anywhere (drafts included); {@code duplicateStatus} is the claimed-receipt
     * check that submission enforces (see {@code ReceiptDuplicateService}).
     */
    private ReceiptResponse toResponseWithDuplicateCheck(Receipt entity) {
        ReceiptDuplicateService.DuplicateSummary duplicate = receiptDuplicateService.summarize(entity);
        UUID fileReuseOf = entity.getFileHash() == null ? null
                : receiptRepository.findByFileHash(entity.getFileHash()).stream()
                        .map(Receipt::getReceiptId)
                        .filter(otherId -> !otherId.equals(entity.getReceiptId()))
                        .findFirst()
                        .orElse(null);
        return receiptMapper.toResponse(entity, fileReuseOf != null, fileReuseOf, duplicate);
    }

    private boolean matchesAt(byte[] header, SignaturePart part) {
        byte[] magic = part.magic();
        int offset = part.offset();
        if (header.length < offset + magic.length) {
            return false;
        }
        return Arrays.equals(magic, 0, magic.length, header, offset, offset + magic.length);
    }

    /** Strips any client-supplied path prefix (e.g. "C:\\fakepath\\x.pdf") down to the bare file name. */
    private String extractBaseFileName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("Uploaded file must have a name");
        }
        String normalized = rawName.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        return lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
    }

    /** Restricts a file name to characters safe in an S3 key / HTTP header — the original name is preserved separately for display. */
    private String sanitizeForKey(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String extractExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && dot < fileName.length() - 1 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private void assertOwnerOrAdmin(ExpenseReport report) {
        CurrentUser caller = currentUserService.getCurrentUser();
        if (hasRole(caller, RoleConstants.ADMIN)) {
            return;
        }
        if (!report.getEmployeeId().equals(caller.employeeId())) {
            throw new AccessDeniedException("You can only manage receipts on your own expense report");
        }
    }

    private void assertViewable(ExpenseReport report) {
        CurrentUser caller = currentUserService.getCurrentUser();
        boolean privileged = hasRole(caller, RoleConstants.ADMIN) || hasRole(caller, RoleConstants.FINANCE)
                || hasRole(caller, RoleConstants.MANAGER) || hasRole(caller, RoleConstants.FINANCE_EXECUTIVE)
                || hasRole(caller, RoleConstants.AP_EXECUTIVE);
        if (privileged) {
            return;
        }
        if (isAssignedApproverOrDelegate(report, caller.employeeId())) {
            return;
        }
        if (!report.getEmployeeId().equals(caller.employeeId())) {
            throw new AccessDeniedException("You can only view receipts on your own expense report");
        }
    }

    private boolean isAssignedApproverOrDelegate(ExpenseReport report, String employeeId) {
        if (employeeId == null || report == null || report.getReportId() == null || approvalAssignmentRepository == null) {
            return false;
        }
        return approvalAssignmentRepository.findByLevelInstance_Report_ReportId(report.getReportId())
                .stream()
                .anyMatch(a -> delegationService == null
                        ? employeeId.equals(a.getApproverId())
                        : delegationService.canAct(employeeId, a.getApproverId()));
    }

    private boolean hasRole(CurrentUser caller, String role) {
        return caller.roles() != null && caller.roles().stream().anyMatch(r -> r.equalsIgnoreCase(role));
    }

    private void assertReportEditable(ExpenseReport report) {
        if (!report.getReportStatus().isEditable()) {
            throw new BusinessRuleViolationException(
                    "Receipts cannot be added or removed while the report is in status " + report.getReportStatus());
        }
    }

    private ExpenseReport findReport(UUID reportId) {
        return expenseReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseReport not found with id: " + reportId));
    }

    private ExpenseLineItem findLineItem(UUID lineItemId) {
        return expenseLineItemRepository.findById(lineItemId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseLineItem not found with id: " + lineItemId));
    }

    private Receipt findReceipt(UUID receiptId) {
        return receiptRepository.findById(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found with id: " + receiptId));
    }
}
