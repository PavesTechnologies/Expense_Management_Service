package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.IntegrationUnavailableException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.DepartmentApproverRequest;
import com.expense_management_service.dto.response.ApproverCandidateResponse;
import com.expense_management_service.dto.response.DepartmentApproverOverviewResponse;
import com.expense_management_service.dto.response.DepartmentApproverResponse;
import com.expense_management_service.entity.DepartmentApprover;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.integration.departments.DepartmentClient;
import com.expense_management_service.integration.departments.dto.DepartmentResponse;
import com.expense_management_service.integration.ums.UmsClient;
import com.expense_management_service.integration.ums.dto.UmsUserResponse;
import com.expense_management_service.mapper.DepartmentApproverMapper;
import com.expense_management_service.repository.DepartmentApproverRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.service.DepartmentApproverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class DepartmentApproverServiceImpl implements DepartmentApproverService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    /** EmployeeCache.employmentStatus value for a current employee (same check as assertApproverExists). */
    private static final String STATUS_EMPLOYEE_ACTIVE = "Active";

    private final DepartmentApproverRepository departmentApproverRepository;
    private final DepartmentApproverMapper departmentApproverMapper;
    private final DepartmentClient departmentClient;
    private final EmployeeCacheRepository employeeCacheRepository;
    private final UmsClient umsClient;

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

    @Override
    @Transactional(readOnly = true)
    public List<DepartmentApproverOverviewResponse> getDepartmentOverview() {
        List<DepartmentResponse> departments;
        try {
            departments = departmentClient.getAllDepartments();
        } catch (RestClientException e) {
            throw new IntegrationUnavailableException("Couldn't load departments from Employee Onboarding. Try again shortly.", e);
        }
        Map<UUID, DepartmentApprover> mappings = new HashMap<>();
        departmentApproverRepository.findAll().forEach(m -> mappings.put(m.getDepartmentUuid(), m));

        List<DepartmentApproverOverviewResponse> rows = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (DepartmentResponse department : departments) {
            if (department.departmentUuid() == null || !seen.add(department.departmentUuid())) {
                continue;
            }
            rows.add(overviewRow(department.departmentUuid(), department.departmentName(), department.description(), true,
                    mappings.get(department.departmentUuid())));
        }
        // Mappings whose department Employee Onboarding no longer lists - shown so they can be removed.
        mappings.values().stream()
                .filter(m -> !seen.contains(m.getDepartmentUuid()))
                .forEach(m -> rows.add(overviewRow(m.getDepartmentUuid(), null, null, false, m)));

        rows.sort(Comparator.comparing(DepartmentApproverOverviewResponse::departmentInOnboarding).reversed()
                .thenComparing(r -> r.departmentName() == null ? "" : r.departmentName().toLowerCase(Locale.ROOT)));
        return rows;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApproverCandidateResponse> getApproverCandidates() {
        List<UmsUserResponse> users;
        try {
            users = umsClient.getAllUsers();
        } catch (RestClientException e) {
            throw new IntegrationUnavailableException("Couldn't load employees from UMS. Try again shortly.", e);
        }
        // Approvals are assigned by employee ID, which UMS doesn't carry: match each UMS user to
        // their employee record by work email, else by UMS user id (the same number in this org).
        Map<String, EmployeeCache> byEmail = new HashMap<>();
        Map<String, EmployeeCache> byEmployeeId = new HashMap<>();
        employeeCacheRepository.findAll().forEach(e -> {
            if (e.getWorkEmail() != null) byEmail.put(e.getWorkEmail().trim().toLowerCase(Locale.ROOT), e);
            if (e.getEmployeeId() != null) byEmployeeId.put(e.getEmployeeId(), e);
        });

        return users.stream()
                .filter(UmsUserResponse::isActive)
                .map(user -> {
                    EmployeeCache employee = user.mail() != null ? byEmail.get(user.mail().trim().toLowerCase(Locale.ROOT)) : null;
                    if (employee == null && user.userId() != null) {
                        employee = byEmployeeId.get(String.valueOf(user.userId()));
                    }
                    String name = fullName(user.firstName(), user.lastName());
                    if (employee == null) {
                        return new ApproverCandidateResponse(null, name, user.mail(), user.userUuid(), null, null, false,
                                "No employee record in Employee Onboarding");
                    }
                    boolean active = STATUS_EMPLOYEE_ACTIVE.equalsIgnoreCase(employee.getEmploymentStatus());
                    return new ApproverCandidateResponse(employee.getEmployeeId(),
                            name != null ? name : fullName(employee.getFirstName(), employee.getLastName()),
                            user.mail() != null ? user.mail() : employee.getWorkEmail(), user.userUuid(),
                            employee.getDepartmentUuid(), employee.getEmploymentStatus(), active,
                            active ? null : "Employment status is " + employee.getEmploymentStatus());
                })
                .sorted(Comparator.comparing(ApproverCandidateResponse::selectable).reversed()
                        .thenComparing(c -> c.name() == null ? "" : c.name().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private DepartmentApproverOverviewResponse overviewRow(UUID departmentUuid, String name, String description,
                                                           boolean inOnboarding, DepartmentApprover mapping) {
        if (mapping == null) {
            return new DepartmentApproverOverviewResponse(departmentUuid, name, description, inOnboarding,
                    null, null, null, null, null, null);
        }
        EmployeeCache approver = employeeCacheRepository.findByEmployeeId(mapping.getApproverEmployeeId()).orElse(null);
        return new DepartmentApproverOverviewResponse(departmentUuid, name, description, inOnboarding,
                mapping.getDepartmentApproverId(), mapping.getApproverEmployeeId(),
                approver != null ? fullName(approver.getFirstName(), approver.getLastName()) : null,
                approver != null ? approver.getWorkEmail() : null,
                approver != null && STATUS_EMPLOYEE_ACTIVE.equalsIgnoreCase(approver.getEmploymentStatus()),
                mapping.getStatus());
    }

    private static String fullName(String first, String last) {
        String name = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return name.isEmpty() ? null : name;
    }

    private void assertDepartmentExists(UUID departmentUuid) {
        boolean exists;
        try {
            exists = departmentClient.existsById(departmentUuid);
        } catch (RestClientException e) {
            throw new IntegrationUnavailableException("Couldn't check the department with Employee Onboarding. Try again shortly.", e);
        }
        if (!exists) {
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
