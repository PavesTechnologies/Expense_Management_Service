package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.response.CashAdvanceDocumentResponse;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceDocument;
import com.expense_management_service.enums.CashAdvanceDocumentCategory;
import com.expense_management_service.mapper.CashAdvanceDocumentMapper;
import com.expense_management_service.repository.CashAdvanceDocumentRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.RoleConstants;
import com.expense_management_service.service.CashAdvanceDocumentService;
import com.expense_management_service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Supporting documents for a cash advance request.
 *
 * <p>Storage order: the file is written to object storage first, then its metadata row is saved in the same transaction
 * as the advance lock. If the metadata save fails, the stored object is removed again. A removed document is
 * soft-deleted; its object is removed from storage only after the removal commits, so a rolled-back removal never loses
 * a file.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashAdvanceDocumentServiceImpl implements CashAdvanceDocumentService {

    private final CashAdvanceRepository cashAdvanceRepository;
    private final CashAdvanceDocumentRepository documentRepository;
    private final StorageService storageService;
    private final CurrentUserService currentUserService;
    private final CashAdvanceDocumentValidator validator;
    private final CashAdvanceDocumentMapper mapper;

    /** Signed links expire after five minutes. */
    private static final Duration LINK_TTL = Duration.ofMinutes(5);

    @Value("${cash-advance.document.max-file-size-bytes:5242880}")
    private long maxFileSizeBytes = 5L * 1024 * 1024;

    @Value("${cash-advance.document.max-files:5}")
    private int maxFiles = 5;

    @Override
    @Transactional
    public CashAdvanceDocumentResponse upload(UUID advanceId, CashAdvanceDocumentCategory category, MultipartFile file) {
        CashAdvance advance = lockAdvance(advanceId);
        assertOwnerAndDraft(advance);
        if (category == null) {
            throw new BusinessRuleViolationException("Choose a category for the document.");
        }
        byte[] bytes = readBytes(file);
        validator.validate(file.getOriginalFilename(), file.getContentType(), bytes, maxFileSizeBytes);

        long attached = documentRepository.countByAdvance_AdvanceIdAndDeletedAtIsNull(advanceId);
        if (attached >= maxFiles) {
            throw new BusinessRuleViolationException(
                    "A request can have at most " + maxFiles + " supporting documents. Remove one before adding another.");
        }

        String originalName = baseName(file.getOriginalFilename());
        String storedName = UUID.randomUUID() + "-" + sanitize(originalName);
        String objectKey = "cash-advances/" + advance.getEmployeeId() + "/" + advanceId + "/" + storedName;

        // A storage failure here leaves nothing behind: no metadata row has been written yet.
        storageService.upload(objectKey, file);
        removeObjectIfNotCommitted(objectKey);
        try {
            CashAdvanceDocument saved = documentRepository.save(CashAdvanceDocument.builder()
                    .advance(advance)
                    .employeeId(advance.getEmployeeId())
                    .category(category)
                    .originalFileName(originalName)
                    .storedFileName(storedName)
                    .objectKey(objectKey)
                    .contentType(file.getContentType().split(";")[0].trim().toLowerCase(Locale.ROOT))
                    .fileSizeBytes(bytes.length)
                    .uploadedBy(currentUserService.getEmployeeId())
                    .uploadedAt(LocalDateTime.now())
                    .build());
            return mapper.toResponse(saved);
        } catch (RuntimeException ex) {
            // The metadata could not be saved, so the stored object would be unreachable. Remove it.
            removeObjectQuietly(objectKey);
            throw ex;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<CashAdvanceDocumentResponse> list(UUID advanceId) {
        CashAdvance advance = findAdvance(advanceId);
        assertCanView(advance);
        return documentRepository.findByAdvance_AdvanceIdAndDeletedAtIsNullOrderByUploadedAtAsc(advanceId).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public com.expense_management_service.dto.response.ReceiptUrlResponse viewUrl(UUID advanceId, UUID documentId) {
        CashAdvanceDocument document = findActiveDocument(advanceId, documentId);
        Duration ttl = LINK_TTL;
        return new com.expense_management_service.dto.response.ReceiptUrlResponse(
                storageService.generateViewUrl(document.getObjectKey(), ttl), LocalDateTime.now().plus(ttl));
    }

    @Override
    @Transactional(readOnly = true)
    public com.expense_management_service.dto.response.ReceiptUrlResponse downloadUrl(UUID advanceId, UUID documentId) {
        CashAdvanceDocument document = findActiveDocument(advanceId, documentId);
        Duration ttl = LINK_TTL;
        return new com.expense_management_service.dto.response.ReceiptUrlResponse(
                storageService.generateDownloadUrl(document.getObjectKey(), document.getOriginalFileName(), ttl),
                LocalDateTime.now().plus(ttl));
    }

    /** The document, if it is still attached to this request and the caller may see the request. */
    private CashAdvanceDocument findActiveDocument(UUID advanceId, UUID documentId) {
        CashAdvance advance = findAdvance(advanceId);
        assertCanView(advance);
        return documentRepository.findById(documentId)
                .filter(d -> d.getAdvance() != null && advanceId.equals(d.getAdvance().getAdvanceId()) && d.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found on this request: " + documentId));
    }

    @Override
    @Transactional
    public void delete(UUID advanceId, UUID documentId) {
        CashAdvance advance = lockAdvance(advanceId);
        assertOwnerAndDraft(advance);
        CashAdvanceDocument document = documentRepository.findById(documentId)
                .filter(d -> d.getAdvance() != null && advanceId.equals(d.getAdvance().getAdvanceId()) && d.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found on this request: " + documentId));

        document.setDeletedBy(currentUserService.getEmployeeId());
        document.setDeletedAt(LocalDateTime.now());
        documentRepository.save(document);

        // The file goes only after the removal commits; a rollback keeps both the row and the file.
        removeObjectAfterCommit(document.getObjectKey());
    }

    // ------------------------------------------------------------------ helpers

    private CashAdvance lockAdvance(UUID advanceId) {
        return cashAdvanceRepository.findByIdForUpdate(advanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Cash advance not found with id: " + advanceId));
    }

    private CashAdvance findAdvance(UUID advanceId) {
        return cashAdvanceRepository.findById(advanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Cash advance not found with id: " + advanceId));
    }

    /** Only the employee who owns the request may change its documents, and only while it is a draft. */
    private void assertOwnerAndDraft(CashAdvance advance) {
        String actor = currentUserService.getEmployeeId();
        if (actor == null || !actor.equals(advance.getEmployeeId())) {
            throw new AccessDeniedException("Only the employee who owns this request can change its documents.");
        }
        if (!"DRAFT".equalsIgnoreCase(advance.getStatus())) {
            throw new BusinessRuleViolationException(
                    "Documents can only be changed while the request is a draft. Current status: " + advance.getStatus());
        }
    }

    /** Owner, the assigned manager, Finance and Admin can see the documents. Anyone else is refused. */
    private void assertCanView(CashAdvance advance) {
        String actor = currentUserService.getEmployeeId();
        if (actor != null && (actor.equals(advance.getEmployeeId()) || actor.equals(advance.getManagerId()))) {
            return;
        }
        if (hasAnyRole(RoleConstants.ROLE_ADMIN, RoleConstants.ROLE_FINANCE, RoleConstants.ROLE_FINANCE_EXECUTIVE)) {
            return;
        }
        throw new AccessDeniedException("You do not have access to this request's documents.");
    }

    private static boolean hasAnyRole(String... roles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            for (String role : roles) {
                if (role.equals(authority.getAuthority())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] readBytes(MultipartFile file) {
        if (file == null) {
            throw new BusinessRuleViolationException("Choose a file to upload.");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BusinessRuleViolationException("The file could not be read. Try uploading it again.");
        }
    }

    /** The file name the employee gave, without any folder part. */
    static String baseName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "document";
        }
        String name = fileName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        return name.isEmpty() ? "document" : name;
    }

    /** Keeps letters, digits, dot, dash and underscore; everything else becomes an underscore. */
    static String sanitize(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() > 150 ? cleaned.substring(cleaned.length() - 150) : cleaned;
    }

    /** Removes an object written in this transaction if the transaction ends without committing. */
    private void removeObjectIfNotCommitted(String objectKey) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        removeObjectQuietly(objectKey);
                    }
                }
            });
        }
    }

    private void removeObjectAfterCommit(String objectKey) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    removeObjectQuietly(objectKey);
                }
            });
        } else {
            removeObjectQuietly(objectKey);
        }
    }

    /** A failed removal from storage is logged, not raised: the document is already gone from the employee's view. */
    private void removeObjectQuietly(String objectKey) {
        try {
            storageService.delete(objectKey);
        } catch (RuntimeException e) {
            log.error("Could not remove cash advance document object {} from storage: {}", objectKey, e.getMessage());
        }
    }
}
