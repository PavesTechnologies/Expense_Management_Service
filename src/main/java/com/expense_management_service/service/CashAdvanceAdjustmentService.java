package com.expense_management_service.service;

import com.expense_management_service.dto.request.CashAdvanceAdjustmentRequest;
import com.expense_management_service.dto.response.CashAdvanceAdjustmentResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface CashAdvanceAdjustmentService {

    CashAdvanceAdjustmentResponse create(CashAdvanceAdjustmentRequest request);

    CashAdvanceAdjustmentResponse update(UUID adjustmentId, CashAdvanceAdjustmentRequest request);

    CashAdvanceAdjustmentResponse getById(UUID adjustmentId);

    Page<CashAdvanceAdjustmentResponse> getAll(Pageable pageable);

    void delete(UUID adjustmentId);
}
