package com.expense_management_service.service;

import java.util.List;

import com.expense_management_service.dto.request.CashAdvanceAdjustmentRequest;
import com.expense_management_service.dto.response.CashAdvanceAdjustmentResponse;


import java.util.UUID;

public interface CashAdvanceAdjustmentService {

    CashAdvanceAdjustmentResponse create(CashAdvanceAdjustmentRequest request);

    CashAdvanceAdjustmentResponse update(UUID adjustmentId, CashAdvanceAdjustmentRequest request);

    CashAdvanceAdjustmentResponse getById(UUID adjustmentId);

    List<CashAdvanceAdjustmentResponse> getAll();

    void delete(UUID adjustmentId);
}
