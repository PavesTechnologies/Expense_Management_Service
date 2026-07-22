package com.expense_management_service.service;

import com.expense_management_service.dto.request.ApprovalDelegationRequest;
import com.expense_management_service.dto.response.ApprovalDelegationResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ApprovalDelegationService {

    ApprovalDelegationResponse create(ApprovalDelegationRequest request);

    ApprovalDelegationResponse update(UUID delegationId, ApprovalDelegationRequest request);

    ApprovalDelegationResponse getById(UUID delegationId);

    Page<ApprovalDelegationResponse> getAll(Pageable pageable);

    void delete(UUID delegationId);
}
