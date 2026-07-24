package com.expense_management_service.service;

import java.util.List;

import com.expense_management_service.dto.request.ApprovalDelegationRequest;
import com.expense_management_service.dto.response.ApprovalDelegationResponse;


import java.util.UUID;

public interface ApprovalDelegationService {

    ApprovalDelegationResponse create(ApprovalDelegationRequest request);

    ApprovalDelegationResponse update(UUID delegationId, ApprovalDelegationRequest request);

    ApprovalDelegationResponse getById(UUID delegationId);

    List<ApprovalDelegationResponse> getAll();

    void delete(UUID delegationId);
}
