package com.expense_management_service.service;

import java.util.List;

import com.expense_management_service.dto.request.CashAdvanceRequest;
import com.expense_management_service.dto.response.CashAdvanceResponse;


import java.util.UUID;

public interface CashAdvanceService {

    CashAdvanceResponse create(CashAdvanceRequest request);

    CashAdvanceResponse update(UUID advanceId, CashAdvanceRequest request);

    CashAdvanceResponse getById(UUID advanceId);

    List<CashAdvanceResponse> getAll();

    void delete(UUID advanceId);
}
