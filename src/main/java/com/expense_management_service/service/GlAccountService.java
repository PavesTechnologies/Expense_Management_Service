package com.expense_management_service.service;

import com.expense_management_service.dto.request.GlAccountRequest;
import com.expense_management_service.dto.response.GlAccountResponse;

import java.util.List;
import java.util.UUID;

public interface GlAccountService {

    GlAccountResponse create(GlAccountRequest request);

    GlAccountResponse update(UUID glAccountId, GlAccountRequest request);

    GlAccountResponse getById(UUID glAccountId);

    List<GlAccountResponse> getAll();

    /** Active-only, name-ordered list for downstream pickers (e.g. the Expense Category form). */
    List<GlAccountResponse> getActiveAccounts();

    void delete(UUID glAccountId);
}
