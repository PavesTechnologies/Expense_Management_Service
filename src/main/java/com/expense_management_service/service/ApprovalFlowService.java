package com.expense_management_service.service;

import com.expense_management_service.dto.request.ApprovalFlowRequest;
import com.expense_management_service.dto.request.CatchAllFlowRequest;
import com.expense_management_service.dto.response.ApprovalFlowResponse;

import java.util.List;
import java.util.UUID;

public interface ApprovalFlowService {

    ApprovalFlowResponse create(ApprovalFlowRequest request);

    ApprovalFlowResponse update(UUID flowId, ApprovalFlowRequest request);

    ApprovalFlowResponse getById(UUID flowId);

    List<ApprovalFlowResponse> getAll();

    /** Refuses to delete the catch-all flow - it is mandatory and singular. */
    void delete(UUID flowId);

    /** Throws {@code ResourceNotFoundException} if Admin hasn't configured the catch-all flow yet. */
    ApprovalFlowResponse getCatchAllFlow();

    /** Upserts the singleton catch-all flow's levels. */
    ApprovalFlowResponse updateCatchAllFlow(CatchAllFlowRequest request);
}
