package com.expense_management_service.service;

import com.expense_management_service.dto.request.TaxCodeRequest;
import com.expense_management_service.dto.response.TaxCodeResponse;

import java.util.List;
import java.util.UUID;

public interface TaxCodeService {

    TaxCodeResponse create(TaxCodeRequest request);

    TaxCodeResponse update(UUID taxCodeId, TaxCodeRequest request);

    TaxCodeResponse getById(UUID taxCodeId);

    List<TaxCodeResponse> getAll();

    /** Active-only list for pickers (e.g. the Expense Category form's Tax Code dropdown). */
    List<TaxCodeResponse> getActive();

    void delete(UUID taxCodeId);
}
