package com.expense_management_service.service.impl;

import java.util.List;
import java.util.UUID;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.CostCenterRequest;
import com.expense_management_service.dto.response.CostCenterResponse;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.integration.departments.DepartmentClient;
import com.expense_management_service.mapper.CostCenterMapper;
import com.expense_management_service.repository.CostCenterRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.service.CostCenterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class CostCenterServiceImpl implements CostCenterService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";
    private static final String EMPLOYMENT_STATUS_ACTIVE = "Active";

    private final CostCenterRepository costCenterRepository;
    private final CostCenterMapper costCenterMapper;
    private final DepartmentClient departmentClient;
    private final EmployeeCacheRepository employeeCacheRepository;

    @Override
    public CostCenterResponse create(CostCenterRequest request) {
        assertDepartmentExists(request.departmentUuid());
        assertOwnerExists(request.ownerEmployeeId());
        assertCodeNotDuplicate(request.costCenterCode(), null);
        assertNameNotDuplicateWithinDepartment(request.costCenterName(), request.departmentUuid(), null);

        CostCenter entity = costCenterMapper.toEntity(request);
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(STATUS_ACTIVE);
        }

        CostCenter saved = costCenterRepository.save(entity);
        log.info("Created cost center {} ({}) under department {}",
                saved.getCostCenterCode(), saved.getCostCenterName(), saved.getDepartmentUuid());
        return costCenterMapper.toResponse(saved);
    }

    @Override
    public CostCenterResponse update(UUID costCenterId, CostCenterRequest request) {
        CostCenter entity = findEntity(costCenterId);
        assertDepartmentExists(request.departmentUuid());
        assertOwnerExists(request.ownerEmployeeId());
        assertCodeNotDuplicate(request.costCenterCode(), costCenterId);
        assertNameNotDuplicateWithinDepartment(request.costCenterName(), request.departmentUuid(), costCenterId);

        costCenterMapper.updateEntity(entity, request);
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(STATUS_ACTIVE);
        }

        CostCenter saved = costCenterRepository.save(entity);
        log.info("Updated cost center {}", costCenterId);
        return costCenterMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public CostCenterResponse getById(UUID costCenterId) {
        return costCenterMapper.toResponse(findEntity(costCenterId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CostCenterResponse> getAll() {
        return costCenterRepository.findAll().stream().map(costCenterMapper::toResponse).toList();
    }

    /** Soft delete: a cost center may already be referenced by future modules (budgets, expense reports, approvals), so it is marked INACTIVE rather than removed. */
    @Override
    public void delete(UUID costCenterId) {
        CostCenter entity = findEntity(costCenterId);
        entity.setStatus(STATUS_INACTIVE);
        costCenterRepository.save(entity);
        log.info("Soft-deleted cost center {} (marked INACTIVE)", costCenterId);
    }

    private void assertDepartmentExists(UUID departmentUuid) {

    log.info("Checking Department = {}", departmentUuid);

    if (!departmentClient.existsById(departmentUuid)) {

        log.error("Department NOT FOUND");

        throw new IllegalArgumentException(
                "departmentUuid does not exist: " + departmentUuid
        );
    }

    log.info("Department validation successful");
}

    /**
     * Validates against the local {@link EmployeeCacheRepository}, not UMS. {@code ownerEmployeeId}
     * is an EOS {@code employeeId} - the same identifier space as every other approver reference in
     * this system (EmployeeCache.managerEmployeeId, ApprovalDelegation.delegatorId/delegateId,
     * approval-flow approver sources). This previously validated against a UMS {@code user_id}
     * instead, a different identifier space entirely, which meant COST_CENTER_OWNER approver
     * resolution could never actually resolve to a real EOS employee. See V6 migration for the
     * one-time data cleanup this fix required.
     */
    private void assertOwnerExists(String ownerEmployeeId) {
        var owner = employeeCacheRepository.findByEmployeeId(ownerEmployeeId)
                .orElseThrow(() -> new IllegalArgumentException("ownerEmployeeId does not exist: " + ownerEmployeeId));

        if (!EMPLOYMENT_STATUS_ACTIVE.equalsIgnoreCase(owner.getEmploymentStatus())) {
            throw new IllegalArgumentException("Owner employee is not Active: " + ownerEmployeeId);
        }
    }

    private void assertCodeNotDuplicate(String costCenterCode, UUID currentCostCenterId) {
        costCenterRepository.findByCostCenterCodeIgnoreCase(costCenterCode).ifPresent(existing -> {
            if (!existing.getCostCenterId().equals(currentCostCenterId)) {
                throw new DuplicateResourceException("Cost center code already exists: " + costCenterCode);
            }
        });
    }

    private void assertNameNotDuplicateWithinDepartment(String costCenterName, UUID departmentUuid, UUID currentCostCenterId) {
        costCenterRepository.findByCostCenterNameIgnoreCaseAndDepartmentUuid(costCenterName, departmentUuid).ifPresent(existing -> {
            if (!existing.getCostCenterId().equals(currentCostCenterId)) {
                throw new DuplicateResourceException(
                        "Cost center name already exists within this department: " + costCenterName);
            }
        });
    }

    private CostCenter findEntity(UUID costCenterId) {
        return costCenterRepository.findById(costCenterId)
                .orElseThrow(() -> new ResourceNotFoundException("CostCenter not found with id: " + costCenterId));
    }
}
