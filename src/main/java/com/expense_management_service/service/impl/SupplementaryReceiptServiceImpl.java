package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.response.SupplementaryReceiptResponse;
import com.expense_management_service.dto.response.SupplementaryReceiptSuggestionResponse;
import com.expense_management_service.event.ReceiptUploadedEvent;
import com.expense_management_service.repository.ReceiptOcrRepository;
import org.springframework.context.ApplicationEventPublisher;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.entity.ReceiptRelease;
import com.expense_management_service.entity.SupplementaryClaimItem;
import com.expense_management_service.entity.SupplementaryExpenseClaim;
import com.expense_management_service.enums.AuditSource;
import com.expense_management_service.enums.OcrStatus;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.ReceiptReleaseRepository;
import com.expense_management_service.repository.ReceiptRepository;
import com.expense_management_service.repository.SupplementaryExpenseClaimRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.SupplementaryReceiptService;
import com.expense_management_service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SupplementaryReceiptServiceImpl implements SupplementaryReceiptService {

    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "application/pdf");

    private final SupplementaryExpenseClaimRepository claimRepository;
    private final ReceiptRepository receiptRepository;
    private final ReceiptReleaseRepository releaseRepository;
    private final StorageService storageService;
    private final AuditLogRepository auditLogRepository;
    private final CurrentUserService currentUserService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ReceiptOcrRepository receiptOcrRepository;

    @Override
    @Transactional(readOnly = true)
    public SupplementaryReceiptSuggestionResponse suggestionFor(UUID receiptId) {
        Receipt receipt = receiptRepository.findById(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found."));
        String actor = actor();
        if (!actor.equals(receipt.getEmployeeId()) && !actor.equals(receipt.getUploadedBy())) {
            throw new AccessDeniedException("You can read suggestions only for your own receipts.");
        }
        return receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId)
                .map(o -> new SupplementaryReceiptSuggestionResponse(receiptId,
                        o.getProcessingStatus() == null ? null : o.getProcessingStatus().name(),
                        o.getAmount(), o.getCurrencyCode(), o.getReceiptDate(), o.getMerchantName()))
                .orElseGet(() -> new SupplementaryReceiptSuggestionResponse(receiptId, "PROCESSING", null, null, null, null));
    }

    @Override
    public void upload(UUID claimId, UUID itemId, MultipartFile file) {
        SupplementaryExpenseClaim claim = findEditableClaim(claimId);
        SupplementaryClaimItem item = findItem(claim, itemId);
        validateFile(file);
        byte[] bytes = readBytes(file);
        String hash = sha256(bytes);
        if (receiptRepository.existsByFileHashAndSupplementaryClaimItemIsNotNull(hash)) {
            throw new BusinessRuleViolationException("This receipt file is already attached to a supplementary claim.");
        }

        String originalName = baseName(file.getOriginalFilename());
        String storedName = UUID.randomUUID() + "-" + originalName.replaceAll("[^A-Za-z0-9._-]", "_");
        String objectKey = "receipts/" + claim.getEmployeeId() + "/" + claim.getOriginal().getReportId()
                + "/supplementary/" + claim.getClaimId() + "/" + item.getClaimItemId() + "/" + storedName;
        storageService.upload(objectKey, file);

        String actor = actor();
        LocalDateTime now = LocalDateTime.now();
        Receipt receipt = receiptRepository.save(Receipt.builder()
                .report(claim.getOriginal())
                .employeeId(claim.getEmployeeId())
                .originalFileName(originalName)
                .storedFileName(storedName)
                .objectKey(objectKey)
                .contentType(file.getContentType())
                .fileSize((int) file.getSize())
                .uploadedBy(actor)
                .uploadedAt(now)
                .ocrStatus(OcrStatus.UPLOADED.name())
                .fileHash(hash)
                .attachmentKind(Receipt.ATTACHMENT_SUPPLEMENTARY)
                .supplementaryClaimItem(item)
                .build());
        audit(receipt.getReceiptId(), "SUPP_RECEIPT_UPLOADED", null, claim.getClaimNumber(), actor, now);
        // Same event as an ordinary upload, so OCR reads the receipt and its details can fill the expense.
        applicationEventPublisher.publishEvent(new ReceiptUploadedEvent(receipt.getReceiptId()));
    }

    @Override
    public UUID uploadScan(UUID claimId, MultipartFile file) {
        SupplementaryExpenseClaim claim = findEditableClaim(claimId);
        validateFile(file);
        byte[] bytes = readBytes(file);
        String hash = sha256(bytes);
        if (receiptRepository.existsByFileHashAndSupplementaryClaimItemIsNotNull(hash)) {
            throw new BusinessRuleViolationException("This receipt file is already attached to a supplementary claim.");
        }
        String originalName = baseName(file.getOriginalFilename());
        String storedName = UUID.randomUUID() + "-" + originalName.replaceAll("[^A-Za-z0-9._-]", "_");
        String objectKey = "receipts/" + claim.getEmployeeId() + "/" + claim.getOriginal().getReportId()
                + "/supplementary/" + claim.getClaimId() + "/scans/" + storedName;
        storageService.upload(objectKey, file);
        Receipt receipt = receiptRepository.save(Receipt.builder()
                .report(claim.getOriginal())
                .employeeId(claim.getEmployeeId())
                .originalFileName(originalName)
                .storedFileName(storedName)
                .objectKey(objectKey)
                .contentType(file.getContentType())
                .fileSize((int) file.getSize())
                .uploadedBy(actor())
                .uploadedAt(LocalDateTime.now())
                .ocrStatus(OcrStatus.UPLOADED.name())
                .fileHash(hash)
                .attachmentKind(Receipt.ATTACHMENT_SUPPLEMENTARY)
                .build());
        // Same event as an ordinary upload, so OCR reads the receipt.
        applicationEventPublisher.publishEvent(new ReceiptUploadedEvent(receipt.getReceiptId()));
        return receipt.getReceiptId();
    }

    @Override
    public void attach(UUID claimId, UUID itemId, UUID receiptId) {
        SupplementaryExpenseClaim claim = findEditableClaim(claimId);
        SupplementaryClaimItem item = findItem(claim, itemId);
        // Lock the receipt row: two claims cannot attach the same receipt at the same moment.
        Receipt receipt = receiptRepository.findByIdForUpdate(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found."));
        if (!claim.getEmployeeId().equals(receipt.getEmployeeId())) {
            throw new AccessDeniedException("This receipt belongs to another employee.");
        }
        if (receipt.getSupplementaryClaimItem() != null) {
            throw new BusinessRuleViolationException("This receipt is already attached to a supplementary claim. A receipt can be used once.");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!Receipt.ATTACHMENT_SUPPLEMENTARY.equals(receipt.getAttachmentKind())) {
            // An original report receipt is used only after Finance released it, and one release covers one attach.
            ReceiptRelease release = releaseRepository.findFirstByReceiptIdOrderByDecidedAtDesc(receiptId)
                    .filter(r -> ReceiptRelease.RELEASED.equals(r.getStatus()) && r.getConsumedAt() == null)
                    .orElseThrow(() -> new BusinessRuleViolationException(
                            "This receipt has not been released by Finance for a supplementary claim."));
            release.setConsumedAt(now);
            releaseRepository.save(release);
        }
        if (receipt.getFileHash() != null
                && receiptRepository.existsByFileHashAndSupplementaryClaimItemIsNotNull(receipt.getFileHash())) {
            throw new BusinessRuleViolationException("This receipt file is already attached to a supplementary claim.");
        }
        receipt.setSupplementaryClaimItem(item);
        receiptRepository.save(receipt);
        audit(receiptId, "SUPP_RECEIPT_ATTACHED", null, claim.getClaimNumber(), actor(), now);
    }

    @Override
    public void detach(UUID claimId, UUID itemId, UUID receiptId) {
        SupplementaryExpenseClaim claim = findEditableClaim(claimId);
        findItem(claim, itemId);
        Receipt receipt = receiptRepository.findByIdForUpdate(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found."));
        if (receipt.getSupplementaryClaimItem() == null || !itemId.equals(receipt.getSupplementaryClaimItem().getClaimItemId())) {
            throw new BusinessRuleViolationException("This receipt is not attached to that expense.");
        }
        // The receipt goes back to unattached. A released original receipt stays consumed, so it needs a new release.
        receipt.setSupplementaryClaimItem(null);
        receiptRepository.save(receipt);
        audit(receiptId, "SUPP_RECEIPT_DETACHED", claim.getClaimNumber(), null, actor(), LocalDateTime.now());
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<SupplementaryReceiptResponse> releasableForCurrentEmployee() {
        String employeeId = actor();
        return receiptRepository.findByEmployeeIdAndAttachmentKindNot(employeeId, Receipt.ATTACHMENT_SUPPLEMENTARY).stream()
                .filter(r -> r.getSupplementaryClaimItem() == null)
                .filter(r -> releaseRepository.findFirstByReceiptIdOrderByDecidedAtDesc(r.getReceiptId())
                        .map(rel -> ReceiptRelease.RELEASED.equals(rel.getStatus()) && rel.getConsumedAt() == null)
                        .orElse(false))
                .map(SupplementaryReceiptServiceImpl::toResponse)
                .toList();
    }

    @Override
    public void detachAllForClaim(UUID claimId) {
        List<Receipt> attached = receiptRepository.findBySupplementaryClaimItem_Claim_ClaimId(claimId);
        for (Receipt receipt : attached) {
            receipt.setSupplementaryClaimItem(null);
        }
        receiptRepository.saveAll(attached);
    }

    @Override
    public void deleteAllForItem(UUID itemId) {
        List<Receipt> attached = receiptRepository.findBySupplementaryClaimItem_ClaimItemId(itemId);
        LocalDateTime now = LocalDateTime.now();
        String actor = actor();
        for (Receipt receipt : attached) {
            // Same order as a report receipt delete: the stored file first, then the row.
            safeDeleteFromStorage(receipt.getObjectKey());
            audit(receipt.getReceiptId(), "SUPP_RECEIPT_DELETED_WITH_EXPENSE", null, null, actor, now);
        }
        receiptRepository.deleteAll(attached);
    }

    private void safeDeleteFromStorage(String objectKey) {
        try {
            storageService.delete(objectKey);
        } catch (RuntimeException ex) {
            // The row is removed either way, so a failed storage delete is logged for cleanup instead of blocking the expense delete.
            log.error("Failed to delete a supplementary receipt file from storage; it may need manual cleanup", ex);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Employee or the Finance creator, while the claim is draft or returned for clarification. */
    private SupplementaryExpenseClaim findEditableClaim(UUID claimId) {
        SupplementaryExpenseClaim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Supplementary claim not found."));
        String actor = actor();
        if (!actor.equals(claim.getEmployeeId()) && !actor.equals(claim.getCreatedBy())) {
            throw new AccessDeniedException("Only the employee or the creator can change this claim.");
        }
        if (!SupplementaryClaimServiceImpl.EDITABLE.contains(claim.getStatus())) {
            throw new BusinessRuleViolationException("This claim is " + claim.getStatus() + " and its receipts can no longer be changed.");
        }
        return claim;
    }

    private static SupplementaryClaimItem findItem(SupplementaryExpenseClaim claim, UUID itemId) {
        return claim.getItems().stream()
                .filter(i -> itemId.equals(i.getClaimItemId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Expense item not found on this claim."));
    }

    private static void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleViolationException("Choose a receipt file to upload.");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BusinessRuleViolationException("The receipt file is larger than 10 MB.");
        }
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(type)) {
            throw new BusinessRuleViolationException("Upload a JPG, PNG, WEBP or PDF receipt.");
        }
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessRuleViolationException("The receipt file could not be read. Try again.");
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String baseName(String name) {
        if (name == null || name.isBlank()) {
            return "receipt";
        }
        String trimmed = name.replace('\\', '/');
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    private String actor() {
        String id = currentUserService.getEmployeeId();
        if (id == null || id.isBlank()) {
            throw new AccessDeniedException("Your account has no employee id, so it cannot manage receipts.");
        }
        return id;
    }

    private void audit(UUID receiptId, String action, String oldValue, String newValue, String actor, LocalDateTime at) {
        auditLogRepository.save(AuditLog.builder()
                .entityName("Receipt")
                .entityId(receiptId)
                .action(action)
                .oldValue(oldValue)
                .newValue(newValue)
                .performedBy(actor)
                .performedAt(at)
                .source(AuditSource.EMPLOYEE)
                .build());
    }

    private static SupplementaryReceiptResponse toResponse(Receipt r) {
        return new SupplementaryReceiptResponse(r.getReceiptId(), r.getOriginalFileName(), r.getAttachmentKind(),
                r.getFileSize(), r.getUploadedAt());
    }
}
