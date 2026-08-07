package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.DepartmentApproverRequest;
import com.expense_management_service.dto.response.DepartmentApproverResponse;
import com.expense_management_service.entity.DepartmentApprover;
import com.expense_management_service.integration.departments.DepartmentClient;
import com.expense_management_service.mapper.DepartmentApproverMapper;
import com.expense_management_service.repository.DepartmentApproverRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.service.DepartmentApproverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class DepartmentApproverServiceImpl implements DepartmentApproverService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final DepartmentApproverRepository departmentApproverRepository;
    private final DepartmentApproverMapper departmentApproverMapper;
    private final DepartmentClient departmentClient;
    private final EmployeeCacheRepository employeeCacheRepository;

    @Override
    public DepartmentApproverResponse create(DepartmentApproverRequest request) {
        assertDepartmentExists(request.departmentUuid());
        assertApproverExists(request.approverEmployeeId());
        assertNotDuplicateForDepartment(request.departmentUuid(), null);

        DepartmentApprover entity = departmentApproverMapper.toEntity(request);
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(STATUS_ACTIVE);
        }
        DepartmentApprover saved = departmentApproverRepository.save(entity);
        log.info("Created department approver mapping {} -> {}", saved.getDepartmentUuid(), saved.getApproverEmployeeId());
        return departmentApproverMapper.toResponse(saved);
    }

    @Override
    public DepartmentApproverResponse update(UUID departmentApproverId, DepartmentApproverRequest request) {
        DepartmentApprover entity = findEntity(departmentApproverId);
        assertDepartmentExists(request.departmentUuid());
        assertApproverExists(request.approverEmployeeId());
        assertNotDuplicateForDepartment(request.departmentUuid(), departmentApproverId);

        departmentApproverMapper.updateEntity(entity, request);
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(STATUS_ACTIVE);
        }
        DepartmentApprover saved = departmentApproverRepository.save(entity);
        log.info("Updated department approver mapping {}", departmentApproverId);
        return departmentApproverMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public DepartmentApproverResponse getById(UUID departmentApproverId) {
        return departmentApproverMapper.toResponse(findEntity(departmentApproverId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DepartmentApproverResponse> getAll() {
        return departmentApproverRepository.findAll().stream().map(departmentApproverMapper::toResponse).toList();
    }

    @Override
    public void delete(UUID departmentApproverId) {
        departmentApproverRepository.delete(findEntity(departmentApproverId));
        log.info("Deleted department approver mapping {}", departmentApproverId);
    }

    private void assertDepartmentExists(UUID departmentUuid) {
        if (!departmentClient.existsById(departmentUuid)) {
            throw new IllegalArgumentException("departmentUuid does not exist: " + departmentUuid);
        }
    }

    private void assertApproverExists(String approverEmployeeId) {
        var employee = employeeCacheRepository.findByEmployeeId(approverEmployeeId)
                .orElseThrow(() -> new IllegalArgumentException("approverEmployeeId does not exist: " + approverEmployeeId));
        if (!"Active".equalsIgnoreCase(employee.getEmploymentStatus())) {
            throw new IllegalArgumentException("approverEmployeeId is not an Active employee: " + approverEmployeeId);
        }
    }

    private void assertNotDuplicateForDepartment(UUID departmentUuid, UUID currentId) {
        departmentApproverRepository.findByDepartmentUuid(departmentUuid).ifPresent(existing -> {
            if (!existing.getDepartmentApproverId().equals(currentId)) {
                throw new DuplicateResourceException("A department approver mapping already exists for department " + departmentUuid);
            }
        });
    }

    private DepartmentApprover findEntity(UUID departmentApproverId) {
        return departmentApproverRepository.findById(departmentApproverId)
                .orElseThrow(() -> new ResourceNotFoundException("DepartmentApprover not found with id: " + departmentApproverId));
    }
}
