package com.expense_management_service.service.impl;

import com.expense_management_service.common.CriteriaPatternEvaluator;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ApprovalFlowRequest;
import com.expense_management_service.dto.request.ApprovalLevelRequest;
import com.expense_management_service.dto.request.CatchAllFlowRequest;
import com.expense_management_service.dto.response.ApprovalFlowResponse;
import com.expense_management_service.entity.ApprovalFlow;
import com.expense_management_service.entity.ApprovalLevel;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.enums.ApproverSourceType;
import com.expense_management_service.enums.CriterionField;
import com.expense_management_service.enums.CriterionOperator;
import com.expense_management_service.mapper.ApprovalFlowMapper;
import com.expense_management_service.repository.ApprovalFlowRepository;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.service.ApprovalFlowService;
import com.expense_management_service.enums.NotificationCategory;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.NotificationDraft;
import com.expense_management_service.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ApprovalFlowServiceImpl implements ApprovalFlowService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final int MAX_LEVELS_PER_FLOW = 10;
    private static final String CATCH_ALL_NAME = "Catch-All";

    private final ApprovalFlowRepository approvalFlowRepository;
    private final ApprovalFlowMapper approvalFlowMapper;
    private final AuditLogRepository auditLogRepository;
    private final NotificationService notificationService;
    private final CurrentUserService currentUserService;

    @Override
    public ApprovalFlowResponse create(ApprovalFlowRequest request) {
        assertLevelsValid(request.levels());
        assertCriteriaPatternValid(request);
        assertPriorityNotDuplicated(request.priority(), null);

        ApprovalFlow entity = approvalFlowMapper.toEntity(request);
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(STATUS_ACTIVE);
        }
        ApprovalFlow saved = approvalFlowRepository.save(entity);
        log.info("Created approval flow {} ({}) at priority {}", saved.getFlowId(), saved.getName(), saved.getPriority());
        alertAdmins("created", saved.getName());
        return approvalFlowMapper.toResponse(saved);
    }

    @Override
    public ApprovalFlowResponse update(UUID flowId, ApprovalFlowRequest request) {
        ApprovalFlow entity = findEntity(flowId);
        assertNotCatchAll(entity, "update");
        assertLevelsValid(request.levels());
        assertCriteriaPatternValid(request);
        assertPriorityNotDuplicated(request.priority(), flowId);

        approvalFlowMapper.updateEntity(entity, request);
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(STATUS_ACTIVE);
        }
        ApprovalFlow saved = approvalFlowRepository.save(entity);
        log.info("Updated approval flow {}", flowId);
        alertAdmins("updated", saved.getName());
        return approvalFlowMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ApprovalFlowResponse getById(UUID flowId) {
        return approvalFlowMapper.toResponse(findEntity(flowId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApprovalFlowResponse> getAll() {
        return approvalFlowRepository.findAll().stream().map(approvalFlowMapper::toResponse).toList();
    }

    @Override
    public void delete(UUID flowId) {
        ApprovalFlow entity = findEntity(flowId);
        assertNotCatchAll(entity, "delete");
        approvalFlowRepository.delete(entity);
        log.info("Deleted approval flow {}", flowId);
        alertAdmins("deleted", entity.getName());
    }

    /**
     * Tells every admin an approval flow changed - routing for new submissions changes with it.
     * Stored in this transaction (rolled back with it) and pushed only after commit. Never fails the save.
     */
    private void alertAdmins(String action, String flowName) {
        try {
            String actor = currentUserService.getCurrentUser().name();
            notificationService.notifyRole(NotificationService.ROLE_ADMIN, NotificationDraft.builder()
                    .category(NotificationCategory.INFO)
                    .eventType("APPROVAL_FLOW_CHANGED")
                    .title("Approval flow \"" + flowName + "\" " + action)
                    .message((actor != null ? actor : "An admin") + " " + action + " the approval flow \"" + flowName
                            + "\". Reports submitted from now on are routed with the new configuration.")
                    .actorName(actor)
                    .statusLabel("Configuration changed")
                    .actionLabel("deleted".equals(action) ? null : "View flows")
                    .link("/expense-management/approval-rules/flows")
                    .build());
        } catch (Exception ex) {
            log.warn("Could not raise admin alert for approval flow change", ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ApprovalFlowResponse getCatchAllFlow() {
        return approvalFlowMapper.toResponse(findCatchAllFlow());
    }

    @Override
    public ApprovalFlowResponse updateCatchAllFlow(CatchAllFlowRequest request, String actingEmployeeId) {
        assertLevelsValid(request.levels());

        ApprovalFlow catchAll = approvalFlowRepository.findByIsCatchAllTrue().orElseGet(() -> ApprovalFlow.builder()
                .name(CATCH_ALL_NAME)
                .isCatchAll(true)
                .status(STATUS_ACTIVE)
                .build());

        // Snapshotted BEFORE replaceLevels mutates this same list in place (clear() + addAll()) -
        // this is the only record of what existed before a full-replace save, since there is no
        // confirmation step and the previous levels are otherwise unrecoverable once orphanRemoval
        // deletes them.
        String levelsBefore = summarizeLevels(catchAll.getLevels());
        approvalFlowMapper.replaceLevels(catchAll, request.levels());
        ApprovalFlow saved = approvalFlowRepository.save(catchAll);
        String levelsAfter = summarizeLevels(saved.getLevels());

        auditLogRepository.save(AuditLog.builder()
                .entityName("ApprovalFlow")
                .entityId(saved.getFlowId())
                .action("CATCH_ALL_LEVELS_UPDATED")
                .oldValue(levelsBefore)
                .newValue(levelsAfter)
                .performedBy(actingEmployeeId)
                .performedAt(LocalDateTime.now())
                .build());

        log.info("Updated catch-all approval flow {} by {} - levels: [{}] -> [{}]",
                saved.getFlowId(), actingEmployeeId, levelsBefore, levelsAfter);
        return approvalFlowMapper.toResponse(saved);
    }

    /** "L{order}[{levelType}: {sourceType}({sourceReference}), ...]; ..." - a compact, human-readable snapshot for the audit trail, not meant to be machine-parsed back. */
    private String summarizeLevels(List<ApprovalLevel> levels) {
        return levels.stream()
                .sorted(Comparator.comparing(ApprovalLevel::getLevelOrder))
                .map(level -> "L" + level.getLevelOrder() + "[" + level.getLevelType() + ": "
                        + level.getApprovers().stream()
                                .map(a -> a.getSourceReference() != null
                                        ? a.getSourceType() + "(" + a.getSourceReference() + ")"
                                        : String.valueOf(a.getSourceType()))
                                .collect(Collectors.joining(", "))
                        + "]")
                .collect(Collectors.joining("; "));
    }

    private void assertLevelsValid(List<ApprovalLevelRequest> levels) {
        if (levels.size() > MAX_LEVELS_PER_FLOW) {
            throw new IllegalArgumentException("A flow may have at most " + MAX_LEVELS_PER_FLOW + " levels");
        }
        var levelOrders = levels.stream().map(ApprovalLevelRequest::levelOrder).collect(Collectors.toSet());
        if (levelOrders.size() != levels.size()) {
            throw new IllegalArgumentException("levelOrder values must be unique within a flow");
        }
        for (ApprovalLevelRequest level : levels) {
            for (var approver : level.approvers()) {
                if (approver.sourceType() == ApproverSourceType.NAMED_USER
                        && (approver.sourceReference() == null || approver.sourceReference().isBlank())) {
                    throw new IllegalArgumentException(
                            "Level " + level.levelOrder() + ": sourceReference is required for NAMED_USER approver entries");
                }
            }
        }
    }

    private void assertCriteriaPatternValid(ApprovalFlowRequest request) {
        var knownIndices = request.criteria().stream()
                .map(com.expense_management_service.dto.request.ApprovalFlowCriterionRequest::index)
                .collect(Collectors.toSet());
        CriteriaPatternEvaluator.assertValid(request.criteriaPattern(), knownIndices);

        for (var criterion : request.criteria()) {
            if (criterion.field() != CriterionField.AMOUNT
                    && criterion.operator() != CriterionOperator.EQUALS
                    && criterion.operator() != CriterionOperator.NOT_EQUALS) {
                throw new IllegalArgumentException(
                        "Criterion " + criterion.index() + ": operator " + criterion.operator()
                                + " is only valid for AMOUNT, not " + criterion.field());
            }
        }
    }

    private void assertPriorityNotDuplicated(Integer priority, UUID currentFlowId) {
        if (priority == null) {
            return;
        }
        approvalFlowRepository.findAll().stream()
                .filter(f -> !f.getIsCatchAll())
                .filter(f -> priority.equals(f.getPriority()))
                .filter(f -> !f.getFlowId().equals(currentFlowId))
                .findFirst()
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Another flow already uses priority " + priority);
                });
    }

    private void assertNotCatchAll(ApprovalFlow entity, String action) {
        if (Boolean.TRUE.equals(entity.getIsCatchAll())) {
            throw new IllegalArgumentException(
                    "The catch-all flow cannot be " + action + "d through this endpoint - use the catch-all-specific endpoint");
        }
    }

    private ApprovalFlow findCatchAllFlow() {
        return approvalFlowRepository.findByIsCatchAllTrue()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No catch-all approval flow is configured yet - every deployment must configure one before any report can be submitted"));
    }

    private ApprovalFlow findEntity(UUID flowId) {
        return approvalFlowRepository.findById(flowId)
                .orElseThrow(() -> new ResourceNotFoundException("ApprovalFlow not found with id: " + flowId));
    }
}
