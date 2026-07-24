package com.expense_management_service.service;

import java.util.List;

import com.expense_management_service.dto.request.VerificationQueryRequest;
import com.expense_management_service.dto.response.VerificationQueryResponse;


import java.util.UUID;

public interface VerificationQueryService {

    VerificationQueryResponse create(VerificationQueryRequest request);

    VerificationQueryResponse update(UUID queryId, VerificationQueryRequest request);

    VerificationQueryResponse getById(UUID queryId);

    List<VerificationQueryResponse> getAll();

    void delete(UUID queryId);
}
