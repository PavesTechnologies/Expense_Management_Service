package com.expense_management_service.service;

import com.expense_management_service.dto.request.CostCenterRequest;
import com.expense_management_service.dto.response.CostCenterResponse;
import java.util.List;
import java.util.UUID;

public interface CostCenterService {

    CostCenterResponse create(CostCenterRequest request);

    CostCenterResponse update(UUID costCenterId, CostCenterRequest request);

    CostCenterResponse getById(UUID costCenterId);

    List<CostCenterResponse> getAll();

    void delete(UUID costCenterId);
}
