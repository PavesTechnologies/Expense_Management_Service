package com.expense_management_service.service;

import com.expense_management_service.dto.request.GlAccountRequest;
import com.expense_management_service.dto.response.GlAccountResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface GlAccountService {

    GlAccountResponse create(GlAccountRequest request);

    GlAccountResponse update(UUID glAccountId, GlAccountRequest request);

    GlAccountResponse getById(UUID glAccountId);

    Page<GlAccountResponse> getAll(Pageable pageable);

    void delete(UUID glAccountId);
}
