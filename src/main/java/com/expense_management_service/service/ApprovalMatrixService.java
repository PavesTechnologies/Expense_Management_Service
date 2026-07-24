package com.expense_management_service.service;

import com.expense_management_service.dto.request.ApprovalMatrixRequest;
import com.expense_management_service.dto.response.ApprovalMatrixResponse;
import java.util.List;
import java.util.UUID;

public interface ApprovalMatrixService {

    ApprovalMatrixResponse create(ApprovalMatrixRequest request);

    ApprovalMatrixResponse update(UUID matrixId, ApprovalMatrixRequest request);

    ApprovalMatrixResponse getById(UUID matrixId);

    List<ApprovalMatrixResponse> getAll();

    void delete(UUID matrixId);
}
