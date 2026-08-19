package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.ApprovalFlowRequest;
import com.expense_management_service.dto.request.ApprovalLevelRequest;
import com.expense_management_service.dto.response.ApprovalFlowCriterionResponse;
import com.expense_management_service.dto.response.ApprovalFlowResponse;
import com.expense_management_service.dto.response.ApprovalLevelApproverResponse;
import com.expense_management_service.dto.response.ApprovalLevelResponse;
import com.expense_management_service.entity.ApprovalFlow;
import com.expense_management_service.entity.ApprovalFlowCriterion;
import com.expense_management_service.entity.ApprovalLevel;
import com.expense_management_service.entity.ApprovalLevelApprover;
import com.expense_management_service.enums.LevelType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Structural mapping only - flow-level validation (criteria pattern references only known indices,
 * NAMED_USER entries carry a sourceReference, level count/order) lives in
 * {@code ApprovalFlowServiceImpl}, matching this project's convention (see {@code PolicyRuleMapper}).
 */
@Component
public class ApprovalFlowMapper {

    /** Builds a non-catch-all flow. Caller sets {@code isCatchAll = false} - this mapper never sets it. */
    public ApprovalFlow toEntity(ApprovalFlowRequest request) {
        ApprovalFlow flow = ApprovalFlow.builder()
                .name(request.name())
                .priority(request.priority())
                .criteriaPattern(request.criteriaPattern())
                .isCatchAll(false)
                .status(request.status())
                .build();
        flow.getCriteria().addAll(toCriterionEntities(flow, request));
        flow.getLevels().addAll(toLevelEntities(flow, request.levels()));
        return flow;
    }

    /** Replaces every child collection in place (orphanRemoval handles the deletes). */
    public void updateEntity(ApprovalFlow entity, ApprovalFlowRequest request) {
        entity.setName(request.name());
        entity.setPriority(request.priority());
        entity.setCriteriaPattern(request.criteriaPattern());
        entity.setStatus(request.status());

        entity.getCriteria().clear();
        entity.getCriteria().addAll(toCriterionEntities(entity, request));

        entity.getLevels().clear();
        entity.getLevels().addAll(toLevelEntities(entity, request.levels()));
    }

    /** Replaces only the catch-all flow's levels - name/priority/criteria don't apply to it. */
    public void replaceLevels(ApprovalFlow catchAllFlow, List<ApprovalLevelRequest> levelRequests) {
        catchAllFlow.getLevels().clear();
        catchAllFlow.getLevels().addAll(toLevelEntities(catchAllFlow, levelRequests));
    }

    private List<ApprovalFlowCriterion> toCriterionEntities(ApprovalFlow flow, ApprovalFlowRequest request) {
        List<ApprovalFlowCriterion> criteria = new ArrayList<>();
        for (var criterionRequest : request.criteria()) {
            criteria.add(ApprovalFlowCriterion.builder()
                    .flow(flow)
                    .index(criterionRequest.index())
                    .field(criterionRequest.field())
                    .operator(criterionRequest.operator())
                    .value(criterionRequest.value())
                    .build());
        }
        return criteria;
    }

    private List<ApprovalLevel> toLevelEntities(ApprovalFlow flow, List<ApprovalLevelRequest> levelRequests) {
        List<ApprovalLevel> levels = new ArrayList<>();
        for (var levelRequest : levelRequests) {
            ApprovalLevel level = ApprovalLevel.builder()
                    .flow(flow)
                    .levelOrder(levelRequest.levelOrder())
                    .levelName(levelRequest.levelName())
                    .quorum(levelRequest.quorum())
                    .levelType(levelRequest.levelType() != null ? levelRequest.levelType() : LevelType.APPROVAL)
                    .build();
            for (var approverRequest : levelRequest.approvers()) {
                level.getApprovers().add(ApprovalLevelApprover.builder()
                        .level(level)
                        .entryOrder(approverRequest.entryOrder())
                        .sourceType(approverRequest.sourceType())
                        .sourceReference(approverRequest.sourceReference())
                        .build());
            }
            levels.add(level);
        }
        return levels;
    }

    public ApprovalFlowResponse toResponse(ApprovalFlow entity) {
        return new ApprovalFlowResponse(
                entity.getFlowId(),
                entity.getName(),
                entity.getPriority(),
                entity.getCriteriaPattern(),
                entity.getIsCatchAll(),
                entity.getStatus(),
                entity.getCriteria().stream()
                        .map(c -> new ApprovalFlowCriterionResponse(c.getCriterionId(), c.getIndex(), c.getField(), c.getOperator(), c.getValue()))
                        .toList(),
                entity.getLevels().stream().map(this::toLevelResponse).toList(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ApprovalLevelResponse toLevelResponse(ApprovalLevel level) {
        return new ApprovalLevelResponse(
                level.getLevelId(),
                level.getLevelOrder(),
                level.getLevelName(),
                resolveDisplayName(level.getLevelName(), level.getLevelOrder()),
                level.getQuorum(),
                level.getLevelType(),
                level.getApprovers().stream()
                        .map(a -> new ApprovalLevelApproverResponse(a.getEntryId(), a.getEntryOrder(), a.getSourceType(), a.getSourceReference()))
                        .toList()
        );
    }

    /** Shared fallback so every read path (flow config, approval status, line-item reviews) renders the same label for an unnamed level. */
    public static String resolveDisplayName(String levelName, Integer levelOrder) {
        return (levelName != null && !levelName.isBlank()) ? levelName : "Level " + levelOrder;
    }
}
