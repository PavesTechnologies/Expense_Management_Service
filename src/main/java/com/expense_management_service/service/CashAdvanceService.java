package com.expense_management_service.service;

import com.expense_management_service.dto.request.CashAdvanceRequest;
import com.expense_management_service.dto.response.CashAdvanceResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface CashAdvanceService {

    CashAdvanceResponse create(CashAdvanceRequest request);

    CashAdvanceResponse update(UUID advanceId, CashAdvanceRequest request);

    CashAdvanceResponse getById(UUID advanceId);

    Page<CashAdvanceResponse> getAll(Pageable pageable);

    void delete(UUID advanceId);
}
