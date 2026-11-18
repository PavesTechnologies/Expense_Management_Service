package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ReceiptReleaseDecisionRequest;
import com.expense_management_service.dto.request.ReceiptReleaseRequest;
import com.expense_management_service.dto.response.ReceiptReleaseResponse;
import com.expense_management_service.dto.response.SupplementaryReceiptResponse;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.entity.ReceiptRelease;
import com.expense_management_service.enums.AuditSource;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.ReceiptReleaseRepository;
import com.expense_management_service.repository.ReceiptRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.PermissionConstants;
import com.expense_management_service.service.ReceiptReleaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ReceiptReleaseServiceImpl implements ReceiptReleaseService {

    static final int MIN_DECISION_REASON = 5;

    private final ReceiptReleaseRepository releaseRepository;
    private final ReceiptRepository receiptRepository;
    private final AuditLogRepository auditLogRepository;
    private final CurrentUserService currentUserService;

    @Override
    public ReceiptReleaseResponse request(ReceiptReleaseRequest request) {
        requirePermission(PermissionConstants.RECEIPT_RELEASE_REQUEST);
        String actor = actor();
        Receipt receipt = receiptRepository.findById(request.receiptId())
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found."));
        if (Receipt.ATTACHMENT_SUPPLEMENTARY.equals(receipt.getAttachmentKind())) {
            throw new BusinessRuleViolationException("This receipt was uploaded for a supplementary claim and does not need a release.");
        }
        if (!Receipt.VERIFICATION_REJECTED.equals(receipt.getVerificationStatus())) {
            throw new BusinessRuleViolationException("Only a receipt that Finance rejected can be released for a supplementary claim.");
        }
        if (receipt.getSupplementaryClaimItem() != null) {
            throw new BusinessRuleViolationException("This receipt is already attached to a supplementary claim.");
        }
        if (releaseRepository.existsByReceiptIdAndStatusIn(receipt.getReceiptId(), List.of(ReceiptRelease.REQUESTED))) {
            throw new BusinessRuleViolationException("A release request for this receipt is already waiting for a second Finance user.");
        }
        releaseRepository.findFirstByReceiptIdOrderByDecidedAtDesc(receipt.getReceiptId()).ifPresent(latest -> {
            if (latest.getConsumedAt() != null) {
                throw new BusinessRuleViolationException("This receipt was already used on a supplementary claim and cannot be released again.");
            }
            if (ReceiptRelease.RELEASED.equals(latest.getStatus())) {
                throw new BusinessRuleViolationException("This receipt is already released and waiting to be attached.");
            }
        });

        LocalDateTime now = LocalDateTime.now();
        ReceiptRelease release = releaseRepository.save(ReceiptRelease.builder()
                .receiptId(receipt.getReceiptId())
                .requestedBy(actor)
                .requestReason(request.reason().trim())
                .requestedAt(now)
                .status(ReceiptRelease.REQUESTED)
                .build());
        audit(release, "RELEASE_REQUESTED", null, ReceiptRelease.REQUESTED, request.reason().trim(), actor, now);
        return toResponse(release);
    }

    @Override
    public ReceiptReleaseResponse approve(UUID releaseId, ReceiptReleaseDecisionRequest decision) {
        requirePermission(PermissionConstants.RECEIPT_RELEASE_APPROVE);
        ReceiptRelease release = decidable(releaseId);
        String actor = actor();
        if (actor.equals(release.getRequestedBy())) {
            throw new AccessDeniedException("A different Finance user must approve this release. You requested it.");
        }
        LocalDateTime now = LocalDateTime.now();
        String reason = trimmedOrNull(decision == null ? null : decision.reason());
        release.setStatus(ReceiptRelease.RELEASED);
        release.setDecidedBy(actor);
        release.setDecidedAt(now);
        release.setDecisionReason(reason);
        ReceiptRelease saved = releaseRepository.save(release);
        audit(saved, "RELEASE_APPROVED", ReceiptRelease.REQUESTED, ReceiptRelease.RELEASED, reason, actor, now);
        return toResponse(saved);
    }

    @Override
    public ReceiptReleaseResponse reject(UUID releaseId, ReceiptReleaseDecisionRequest decision) {
        requirePermission(PermissionConstants.RECEIPT_RELEASE_APPROVE);
        ReceiptRelease release = decidable(releaseId);
        String actor = actor();
        if (actor.equals(release.getRequestedBy())) {
            throw new AccessDeniedException("A different Finance user must decide this release. You requested it.");
        }
        String reason = trimmedOrNull(decision == null ? null : decision.reason());
        if (reason == null || reason.length() < MIN_DECISION_REASON) {
            throw new BusinessRuleViolationException("Give a reason for rejecting the release (at least " + MIN_DECISION_REASON + " characters).");
        }
        LocalDateTime now = LocalDateTime.now();
        release.setStatus(ReceiptRelease.RELEASE_REJECTED);
        release.setDecidedBy(actor);
        release.setDecidedAt(now);
        release.setDecisionReason(reason);
        ReceiptRelease saved = releaseRepository.save(release);
        audit(saved, "RELEASE_REJECTED", ReceiptRelease.REQUESTED, ReceiptRelease.RELEASE_REJECTED, reason, actor, now);
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReceiptReleaseResponse> queue() {
        requireAnyPermission(PermissionConstants.RECEIPT_RELEASE_APPROVE, PermissionConstants.RECEIPT_RELEASE_REQUEST);
        return releaseRepository.findByStatusOrderByRequestedAtAsc(ReceiptRelease.REQUESTED).stream()
                .map(ReceiptReleaseServiceImpl::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReceiptReleaseResponse> historyFor(UUID receiptId) {
        requireAnyPermission(PermissionConstants.RECEIPT_RELEASE_APPROVE, PermissionConstants.RECEIPT_RELEASE_REQUEST);
        return releaseRepository.findByReceiptIdOrderByRequestedAtDesc(receiptId).stream()
                .map(ReceiptReleaseServiceImpl::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupplementaryReceiptResponse> candidatesFor(String employeeId) {
        requirePermission(PermissionConstants.RECEIPT_RELEASE_REQUEST);
        return receiptRepository.findByEmployeeIdAndAttachmentKindNot(employeeId, Receipt.ATTACHMENT_SUPPLEMENTARY).stream()
                .filter(r -> Receipt.VERIFICATION_REJECTED.equals(r.getVerificationStatus()))
                .filter(r -> r.getSupplementaryClaimItem() == null)
                .map(r -> new SupplementaryReceiptResponse(r.getReceiptId(), r.getOriginalFileName(), r.getAttachmentKind(),
                        r.getFileSize(), r.getUploadedAt()))
                .toList();
    }

    // ---------------------------------------------------------------- helpers

    private ReceiptRelease decidable(UUID releaseId) {
        ReceiptRelease release = releaseRepository.findByIdForUpdate(releaseId)
                .orElseThrow(() -> new ResourceNotFoundException("Release request not found."));
        if (!ReceiptRelease.REQUESTED.equals(release.getStatus())) {
            throw new BusinessRuleViolationException("This request is already " + release.getStatus() + ".");
        }
        return release;
    }

    private void requirePermission(String permission) {
        List<String> permissions = currentUserService.getPermissions();
        if (permissions == null || !permissions.contains(permission)) {
            throw new AccessDeniedException("Missing permission " + permission + ".");
        }
    }

    private void requireAnyPermission(String first, String second) {
        List<String> permissions = currentUserService.getPermissions();
        if (permissions == null || (!permissions.contains(first) && !permissions.contains(second))) {
            throw new AccessDeniedException("Missing receipt release permission.");
        }
    }

    private String actor() {
        String id = currentUserService.getEmployeeId();
        if (id == null || id.isBlank()) {
            throw new AccessDeniedException("Your account has no employee id, so it cannot decide receipt releases.");
        }
        return id;
    }

    private static String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void audit(ReceiptRelease release, String action, String oldValue, String newValue, String reason, String actor,
                       LocalDateTime at) {
        auditLogRepository.save(AuditLog.builder()
                .entityName("ReceiptRelease")
                .entityId(release.getReleaseId())
                .action(action)
                .oldValue(oldValue)
                .newValue(newValue)
                .reason(reason)
                .performedBy(actor)
                .performedAt(at)
                .source(AuditSource.FINANCE)
                .build());
    }

    private static ReceiptReleaseResponse toResponse(ReceiptRelease r) {
        return new ReceiptReleaseResponse(r.getReleaseId(), r.getReceiptId(), r.getStatus(), r.getRequestedBy(),
                r.getRequestReason(), r.getRequestedAt(), r.getDecidedBy(), r.getDecisionReason(), r.getDecidedAt(),
                r.getConsumedAt());
    }
}
