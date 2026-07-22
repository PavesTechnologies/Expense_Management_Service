package com.expense_management_service.service;

import com.expense_management_service.dto.request.ApprovalMatrixRequest;
import com.expense_management_service.dto.response.ApprovalMatrixResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ApprovalMatrixService {

    ApprovalMatrixResponse create(ApprovalMatrixRequest request);

    ApprovalMatrixResponse update(UUID matrixId, ApprovalMatrixRequest request);

    ApprovalMatrixResponse getById(UUID matrixId);

    Page<ApprovalMatrixResponse> getAll(Pageable pageable);

    void delete(UUID matrixId);
}
