package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ApprovalMatrix;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.entity.SystemConfiguration;
import com.expense_management_service.enums.ApproverType;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.repository.SystemConfigurationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultApproverResolverImplTest {

    @Mock private EmployeeCacheRepository employeeCacheRepository;
    @Mock private SystemConfigurationRepository systemConfigurationRepository;

    private DefaultApproverResolverImpl resolver;

    @BeforeEach
    void setUp() {
        resolver = new DefaultApproverResolverImpl(employeeCacheRepository, systemConfigurationRepository);
    }

    @Test
    void resolve_returnsApproverReferenceDirectly_forUserType() {
        ApprovalMatrix matrixRow = ApprovalMatrix.builder().approverType(ApproverType.USER).approverReference("mgr-jane").build();

        Optional<String> result = resolver.resolve(matrixRow, "EMP-1");

        assertThat(result).contains("mgr-jane");
    }

    @Test
    void resolve_returnsCostCenterOwner_forCostCenterOwnerType() {
        CostCenter costCenter = CostCenter.builder().ownerEmployeeId("cc-owner").build();
        ApprovalMatrix matrixRow = ApprovalMatrix.builder().approverType(ApproverType.COST_CENTER_OWNER).costCenter(costCenter).build();

        Optional<String> result = resolver.resolve(matrixRow, "EMP-1");

        assertThat(result).contains("cc-owner");
    }

    @Test
    void resolve_fallsBackToDefaultApprover_whenCostCenterHasNoOwner() {
        CostCenter costCenter = CostCenter.builder().ownerEmployeeId(null).build();
        ApprovalMatrix matrixRow = ApprovalMatrix.builder().approverType(ApproverType.COST_CENTER_OWNER).costCenter(costCenter).build();
        when(systemConfigurationRepository.findByConfigKey(DefaultApproverResolverImpl.DEFAULT_APPROVER_CONFIG_KEY))
                .thenReturn(Optional.of(SystemConfiguration.builder().configValue("fallback-approver").build()));

        Optional<String> result = resolver.resolve(matrixRow, "EMP-1");

        assertThat(result).contains("fallback-approver");
    }

    @Test
    void resolve_returnsManagerFromEmployeeCache_forManagerType() {
        EmployeeCache cache = EmployeeCache.builder().employeeId("EMP-1").managerEmployeeId("EMP-MGR").build();
        when(employeeCacheRepository.findByEmployeeId("EMP-1")).thenReturn(Optional.of(cache));
        ApprovalMatrix matrixRow = ApprovalMatrix.builder().approverType(ApproverType.MANAGER).build();

        Optional<String> result = resolver.resolve(matrixRow, "EMP-1");

        assertThat(result).contains("EMP-MGR");
    }

    @Test
    void resolve_fallsBackToDefaultApprover_whenEmployeeHasNoManagerOnFile() {
        when(employeeCacheRepository.findByEmployeeId("EMP-1")).thenReturn(Optional.empty());
        when(systemConfigurationRepository.findByConfigKey(DefaultApproverResolverImpl.DEFAULT_APPROVER_CONFIG_KEY))
                .thenReturn(Optional.of(SystemConfiguration.builder().configValue("fallback-approver").build()));
        ApprovalMatrix matrixRow = ApprovalMatrix.builder().approverType(ApproverType.MANAGER).build();

        Optional<String> result = resolver.resolve(matrixRow, "EMP-1");

        assertThat(result).contains("fallback-approver");
    }

    @Test
    void resolve_fallsBackToDefaultApprover_forRoleType() {
        when(systemConfigurationRepository.findByConfigKey(DefaultApproverResolverImpl.DEFAULT_APPROVER_CONFIG_KEY))
                .thenReturn(Optional.of(SystemConfiguration.builder().configValue("fallback-approver").build()));
        ApprovalMatrix matrixRow = ApprovalMatrix.builder().approverType(ApproverType.ROLE).approverReference("FINANCE").build();

        Optional<String> result = resolver.resolve(matrixRow, "EMP-1");

        assertThat(result).contains("fallback-approver");
    }

    @Test
    void resolve_returnsEmpty_whenNothingResolvesAndNoDefaultApproverIsConfigured() {
        when(employeeCacheRepository.findByEmployeeId("EMP-1")).thenReturn(Optional.empty());
        when(systemConfigurationRepository.findByConfigKey(DefaultApproverResolverImpl.DEFAULT_APPROVER_CONFIG_KEY))
                .thenReturn(Optional.empty());
        ApprovalMatrix matrixRow = ApprovalMatrix.builder().approverType(ApproverType.MANAGER).build();

        Optional<String> result = resolver.resolve(matrixRow, "EMP-1");

        assertThat(result).isEmpty();
    }
}
