package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalLevelApprover;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.DepartmentApprover;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.ApproverSourceType;
import com.expense_management_service.repository.DepartmentApproverRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultApproverSourceResolverImplTest {

    @Mock private EmployeeCacheRepository employeeCacheRepository;
    @Mock private DepartmentApproverRepository departmentApproverRepository;

    private DefaultApproverSourceResolverImpl resolver;

    private ExpenseReport report(String employeeId) {
        return ExpenseReport.builder().reportId(UUID.randomUUID()).employeeId(employeeId).build();
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        resolver = new DefaultApproverSourceResolverImpl(employeeCacheRepository, departmentApproverRepository);
    }

    @Test
    void resolve_returnsSourceReference_forNamedUser() {
        ApprovalLevelApprover entry = ApprovalLevelApprover.builder().sourceType(ApproverSourceType.NAMED_USER).sourceReference("5100099").build();

        assertThat(resolver.resolve(entry, report("5100001"))).contains("5100099");
    }

    @Test
    void resolve_returnsManagerEmployeeId_forReportingManager() {
        ApprovalLevelApprover entry = ApprovalLevelApprover.builder().sourceType(ApproverSourceType.REPORTING_MANAGER).build();
        when(employeeCacheRepository.findByEmployeeId("5100001")).thenReturn(Optional.of(
                EmployeeCache.builder().employeeId("5100001").managerEmployeeId("5100002").build()));

        assertThat(resolver.resolve(entry, report("5100001"))).contains("5100002");
    }

    @Test
    void resolve_returnsEmpty_forReportingManager_whenNoManagerOnFile() {
        ApprovalLevelApprover entry = ApprovalLevelApprover.builder().sourceType(ApproverSourceType.REPORTING_MANAGER).build();
        when(employeeCacheRepository.findByEmployeeId("5100001")).thenReturn(Optional.empty());

        assertThat(resolver.resolve(entry, report("5100001"))).isEmpty();
    }

    @Test
    void resolve_returnsDepartmentApprover_forDepartmentOwner() {
        UUID departmentUuid = UUID.randomUUID();
        ApprovalLevelApprover entry = ApprovalLevelApprover.builder().sourceType(ApproverSourceType.DEPARTMENT_OWNER).build();
        when(employeeCacheRepository.findByEmployeeId("5100001")).thenReturn(Optional.of(
                EmployeeCache.builder().employeeId("5100001").departmentUuid(departmentUuid.toString()).build()));
        when(departmentApproverRepository.findByDepartmentUuid(departmentUuid)).thenReturn(Optional.of(
                DepartmentApprover.builder().departmentUuid(departmentUuid).approverEmployeeId("5100050").build()));

        assertThat(resolver.resolve(entry, report("5100001"))).contains("5100050");
    }

    @Test
    void resolve_returnsEmpty_forDepartmentOwner_whenNoMappingExists() {
        UUID departmentUuid = UUID.randomUUID();
        ApprovalLevelApprover entry = ApprovalLevelApprover.builder().sourceType(ApproverSourceType.DEPARTMENT_OWNER).build();
        when(employeeCacheRepository.findByEmployeeId("5100001")).thenReturn(Optional.of(
                EmployeeCache.builder().employeeId("5100001").departmentUuid(departmentUuid.toString()).build()));
        when(departmentApproverRepository.findByDepartmentUuid(departmentUuid)).thenReturn(Optional.empty());

        assertThat(resolver.resolve(entry, report("5100001"))).isEmpty();
    }

    @Test
    void resolve_returnsCostCenterOwner() {
        ApprovalLevelApprover entry = ApprovalLevelApprover.builder().sourceType(ApproverSourceType.COST_CENTER_OWNER).build();
        CostCenter costCenter = CostCenter.builder().costCenterId(UUID.randomUUID()).ownerEmployeeId("5100077").build();
        ExpenseReport report = ExpenseReport.builder().reportId(UUID.randomUUID()).employeeId("5100001").costCenter(costCenter).build();

        assertThat(resolver.resolve(entry, report)).contains("5100077");
    }

    @Test
    void resolve_returnsEmpty_forCostCenterOwner_whenNoOwnerConfigured() {
        ApprovalLevelApprover entry = ApprovalLevelApprover.builder().sourceType(ApproverSourceType.COST_CENTER_OWNER).build();
        CostCenter costCenter = CostCenter.builder().costCenterId(UUID.randomUUID()).ownerEmployeeId(null).build();
        ExpenseReport report = ExpenseReport.builder().reportId(UUID.randomUUID()).employeeId("5100001").costCenter(costCenter).build();

        assertThat(resolver.resolve(entry, report)).isEmpty();
    }
}
