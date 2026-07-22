package com.expense_management_service.service;

import com.expense_management_service.dto.request.VerificationQueryRequest;
import com.expense_management_service.dto.response.VerificationQueryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface VerificationQueryService {

    VerificationQueryResponse create(VerificationQueryRequest request);

    VerificationQueryResponse update(UUID queryId, VerificationQueryRequest request);

    VerificationQueryResponse getById(UUID queryId);

    Page<VerificationQueryResponse> getAll(Pageable pageable);

    void delete(UUID queryId);
}
