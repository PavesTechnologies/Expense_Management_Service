package com.expense_management_service.service.impl;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.expense_management_service.dto.response.EmployeeSummaryResponse;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.service.EmployeeDirectoryService;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Served from EmployeeCache (CDC-synced from Employee Onboarding) rather than having the frontend
 * call Employee Onboarding directly: it is the same employee-ID space approvals resolve against,
 * and it is readable by every role, so non-admin approvers can pick their own delegate.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmployeeDirectoryServiceImpl implements EmployeeDirectoryService {

    /** EmployeeCache.employmentStatus value for a current employee (same check as cost center owners/department approvers). */
    static final String EMPLOYMENT_STATUS_ACTIVE = "Active";

    private final EmployeeCacheRepository employeeCacheRepository;

    @Override
    public List<EmployeeSummaryResponse> getActiveEmployees() {
        return employeeCacheRepository.findByEmploymentStatusIgnoreCase(EMPLOYMENT_STATUS_ACTIVE).stream()
                .filter(e -> e.getEmployeeId() != null && !e.getEmployeeId().isBlank())
                .map(EmployeeDirectoryServiceImpl::toResponse)
                .sorted(Comparator.comparing(EmployeeSummaryResponse::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    static EmployeeSummaryResponse toResponse(EmployeeCache e) {
        String name = Stream.of(e.getFirstName(), e.getLastName())
                .filter(Objects::nonNull).map(String::trim).filter(part -> !part.isEmpty())
                .collect(Collectors.joining(" "));
        return new EmployeeSummaryResponse(e.getEmployeeId(), name.isEmpty() ? e.getEmployeeId() : name,
                e.getWorkEmail(), e.getDepartmentUuid(), e.getManagerEmployeeId());
    }
}
