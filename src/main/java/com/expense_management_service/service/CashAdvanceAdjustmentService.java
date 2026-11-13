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

    /** Reverses an offset. The row is kept for audit; the balance is restored from the remaining active offsets. */
    CashAdvanceAdjustmentResponse reverse(UUID adjustmentId, String reason);

    /** Kept for existing callers: reverses the offset with a generic reason. Financial history is never deleted. */
    void delete(UUID adjustmentId);
}
