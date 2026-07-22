package com.expense_management_service.service;

import com.expense_management_service.dto.request.CostAllocationRequest;
import com.expense_management_service.dto.response.CostAllocationResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface CostAllocationService {

    CostAllocationResponse create(CostAllocationRequest request);

    CostAllocationResponse update(UUID allocationId, CostAllocationRequest request);

    CostAllocationResponse getById(UUID allocationId);

    Page<CostAllocationResponse> getAll(Pageable pageable);

    void delete(UUID allocationId);
}
