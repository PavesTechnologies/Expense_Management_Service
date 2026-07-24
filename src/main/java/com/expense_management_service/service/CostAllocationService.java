package com.expense_management_service.service;

import com.expense_management_service.dto.request.CostAllocationRequest;
import com.expense_management_service.dto.response.CostAllocationResponse;
import java.util.List;
import java.util.UUID;

public interface CostAllocationService {

    CostAllocationResponse create(CostAllocationRequest request);

    CostAllocationResponse update(UUID allocationId, CostAllocationRequest request);

    CostAllocationResponse getById(UUID allocationId);

    List<CostAllocationResponse> getAll();

    void delete(UUID allocationId);
}
