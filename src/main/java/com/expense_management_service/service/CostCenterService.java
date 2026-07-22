package com.expense_management_service.service;

import com.expense_management_service.dto.request.CostCenterRequest;
import com.expense_management_service.dto.response.CostCenterResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface CostCenterService {

    CostCenterResponse create(CostCenterRequest request);

    CostCenterResponse update(UUID costCenterId, CostCenterRequest request);

    CostCenterResponse getById(UUID costCenterId);

    Page<CostCenterResponse> getAll(Pageable pageable);

    void delete(UUID costCenterId);
}
