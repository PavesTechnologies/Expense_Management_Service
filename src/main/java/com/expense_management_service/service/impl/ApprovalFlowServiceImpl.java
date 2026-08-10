package com.expense_management_service.service.impl;

import com.expense_management_service.common.CriteriaPatternEvaluator;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ApprovalFlowRequest;
import com.expense_management_service.dto.request.ApprovalLevelRequest;
import com.expense_management_service.dto.request.CatchAllFlowRequest;
import com.expense_management_service.dto.response.ApprovalFlowResponse;
import com.expense_management_service.entity.ApprovalFlow;
import com.expense_management_service.enums.ApproverSourceType;
import com.expense_management_service.enums.CriterionField;
import com.expense_management_service.enums.CriterionOperator;
import com.expense_management_service.mapper.ApprovalFlowMapper;
import com.expense_management_service.repository.ApprovalFlowRepository;
import com.expense_management_service.service.ApprovalFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    }

    @Override
    @Transactional(readOnly = true)
    public ApprovalFlowResponse getCatchAllFlow() {
        return approvalFlowMapper.toResponse(findCatchAllFlow());
    }

    @Override
    public ApprovalFlowResponse updateCatchAllFlow(CatchAllFlowRequest request) {
        assertLevelsValid(request.levels());

        ApprovalFlow catchAll = approvalFlowRepository.findByIsCatchAllTrue().orElseGet(() -> ApprovalFlow.builder()
                .name(CATCH_ALL_NAME)
                .isCatchAll(true)
                .status(STATUS_ACTIVE)
                .build());

        approvalFlowMapper.replaceLevels(catchAll, request.levels());
        ApprovalFlow saved = approvalFlowRepository.save(catchAll);
        log.info("Updated catch-all approval flow {}", saved.getFlowId());
        return approvalFlowMapper.toResponse(saved);
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
