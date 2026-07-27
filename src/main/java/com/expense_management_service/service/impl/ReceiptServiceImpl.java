package com.expense_management_service.service.impl;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.expense_management_service.common.ReportStatusConstants;
import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.response.ReceiptResponse;
import com.expense_management_service.dto.response.ReceiptUrlResponse;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.mapper.ReceiptMapper;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ReceiptRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.RoleConstants;
import com.expense_management_service.service.ReceiptService;
import com.expense_management_service.storage.StorageException;
import com.expense_management_service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ReceiptServiceImpl implements ReceiptService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("application/pdf", "image/png", "image/jpeg");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "png", "jpg", "jpeg");

    /**
     * File-signature ("magic bytes") of each allowed extension, checked against the actual
     * uploaded bytes — declared Content-Type and file extension are both attacker-controlled
     * (renaming a file changes both at once), so neither alone proves what the file actually is.
     */
    private static final Map<String, byte[]> SIGNATURES_BY_EXTENSION = Map.of(
            "pdf", new byte[] {0x25, 0x50, 0x44, 0x46},
            "png", new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A},
            "jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF},
            "jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}
    );

    private final ReceiptRepository receiptRepository;
    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final StorageService storageService;
    private final CurrentUserService currentUserService;
    private final ReceiptMapper receiptMapper;

    @Value("${receipt.max-file-size-bytes:10485760}")
    private long maxFileSizeBytes;

    @Value("${receipt.presigned-url-ttl-minutes:15}")
    private long presignedUrlTtlMinutes;

    @Override
    public ReceiptResponse upload(UUID lineItemId, MultipartFile file) {
        ExpenseLineItem lineItem = findLineItem(lineItemId);
        ExpenseReport report = lineItem.getReport();
        assertOwnerOrAdmin(report);
        assertReportEditable(report);
        assertFileValid(file);

        String originalFileName = extractBaseFileName(file.getOriginalFilename());
        String storedFileName = UUID.randomUUID() + "-" + sanitizeForKey(originalFileName);
        String objectKey = "receipts/" + report.getEmployeeId() + "/" + report.getReportId() + "/" + lineItemId + "/" + storedFileName;

        storageService.upload(objectKey, file);

        Receipt entity = Receipt.builder()
                .lineItem(lineItem)
                .originalFileName(originalFileName)
                .storedFileName(storedFileName)
                .objectKey(objectKey)
                .contentType(file.getContentType())
                .fileSize((int) file.getSize())
                .uploadedBy(currentUserService.getCurrentUser().employeeId())
                .uploadedAt(LocalDateTime.now())
                .build();

        try {
            // saveAndFlush (not save) so a constraint violation surfaces here, inside the
            // try/catch, rather than later at transaction-commit time — a deferred flush
            // failure would otherwise skip the compensating S3 delete below entirely.
            Receipt saved = receiptRepository.saveAndFlush(entity);
            log.info("Uploaded receipt {} for line item {}", saved.getReceiptId(), lineItemId);
            return receiptMapper.toResponse(saved);
        } catch (RuntimeException ex) {
            log.error("Metadata save failed after storage upload succeeded for line item {} — deleting the now-orphaned file", lineItemId, ex);
            safeDeleteFromStorage(objectKey);
            throw ex;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReceiptResponse> getAllForLineItem(UUID lineItemId) {
        ExpenseLineItem lineItem = findLineItem(lineItemId);
        assertViewable(lineItem.getReport());
        return receiptRepository.findByLineItem_LineItemId(lineItemId).stream().map(receiptMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ReceiptResponse getById(UUID receiptId) {
        Receipt entity = findReceipt(receiptId);
        assertViewable(entity.getLineItem().getReport());
        return receiptMapper.toResponse(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public ReceiptUrlResponse getViewUrl(UUID receiptId) {
        Receipt entity = findReceipt(receiptId);
        assertViewable(entity.getLineItem().getReport());
        Duration ttl = Duration.ofMinutes(presignedUrlTtlMinutes);
        String url = storageService.generateViewUrl(entity.getObjectKey(), ttl);
        log.info("Generated view URL for receipt {}", receiptId);
        return new ReceiptUrlResponse(url, LocalDateTime.now().plus(ttl));
    }

    @Override
    @Transactional(readOnly = true)
    public ReceiptUrlResponse getDownloadUrl(UUID receiptId) {
        Receipt entity = findReceipt(receiptId);
        assertViewable(entity.getLineItem().getReport());
        Duration ttl = Duration.ofMinutes(presignedUrlTtlMinutes);
        String url = storageService.generateDownloadUrl(entity.getObjectKey(), entity.getOriginalFileName(), ttl);
        log.info("Generated download URL for receipt {}", receiptId);
        return new ReceiptUrlResponse(url, LocalDateTime.now().plus(ttl));
    }

    @Override
    public void delete(UUID receiptId) {
        Receipt entity = findReceipt(receiptId);
        ExpenseReport report = entity.getLineItem().getReport();
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

    private void assertFileValid(MultipartFile file) {
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
                    "Unsupported file type: " + file.getContentType() + ". Allowed types: PDF, PNG, JPG, JPEG");
        }
        String extension = extractExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                    "Unsupported file extension: ." + extension + ". Allowed: pdf, png, jpg, jpeg");
        }
        assertContentMatchesDeclaredType(file, extension);
    }

    /**
     * Verifies the file's actual bytes start with the signature expected for its extension.
     * Both the declared Content-Type and the file extension are attacker-controlled (renaming
     * a file changes both at once), so passing those two checks alone does not prove the file
     * actually is a PDF/PNG/JPEG — only its content can.
     */
    private void assertContentMatchesDeclaredType(MultipartFile file, String extension) {
        byte[] signature = SIGNATURES_BY_EXTENSION.get(extension);
        byte[] header;
        try {
            header = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read the uploaded file to verify its type");
        }
        if (header.length < signature.length || !Arrays.equals(signature, 0, signature.length, header, 0, signature.length)) {
            throw new IllegalArgumentException(
                    "File content does not match its declared type (." + extension + ") — the file may be corrupted or mislabeled");
        }
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
                || hasRole(caller, RoleConstants.MANAGER);
        if (privileged) {
            return;
        }
        if (!report.getEmployeeId().equals(caller.employeeId())) {
            throw new AccessDeniedException("You can only view receipts on your own expense report");
        }
    }

    private boolean hasRole(CurrentUser caller, String role) {
        return caller.roles() != null && caller.roles().stream().anyMatch(r -> r.equalsIgnoreCase(role));
    }

    private void assertReportEditable(ExpenseReport report) {
        if (!ReportStatusConstants.isEditable(report.getReportStatus())) {
            throw new BusinessRuleViolationException(
                    "Receipts cannot be added or removed while the report is in status " + report.getReportStatus());
        }
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
