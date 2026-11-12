package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.DepartmentApproverRequest;
import com.expense_management_service.dto.response.DepartmentApproverResponse;
import com.expense_management_service.entity.DepartmentApprover;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.common.exception.IntegrationUnavailableException;
import com.expense_management_service.dto.response.ApproverCandidateResponse;
import com.expense_management_service.dto.response.DepartmentApproverOverviewResponse;
import com.expense_management_service.integration.departments.DepartmentClient;
import com.expense_management_service.integration.departments.dto.DepartmentResponse;
import com.expense_management_service.integration.ums.UmsClient;
import com.expense_management_service.integration.ums.dto.UmsUserResponse;
import com.expense_management_service.mapper.DepartmentApproverMapper;
import com.expense_management_service.repository.DepartmentApproverRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DepartmentApproverServiceImplTest {

    @Mock private DepartmentApproverRepository departmentApproverRepository;
    @Mock private DepartmentClient departmentClient;
    @Mock private EmployeeCacheRepository employeeCacheRepository;
    @Mock private UmsClient umsClient;

    private DepartmentApproverServiceImpl service;

    private UUID departmentUuid;
    private String approverEmployeeId;

    @BeforeEach
    void setUp() {
        service = new DepartmentApproverServiceImpl(departmentApproverRepository, new DepartmentApproverMapper(), departmentClient, employeeCacheRepository, umsClient);
        departmentUuid = UUID.randomUUID();
        approverEmployeeId = "5100014";
    }

    private DepartmentApproverRequest validRequest() {
        return new DepartmentApproverRequest(departmentUuid, approverEmployeeId, "ACTIVE");
    }

    private void stubValid() {
        when(departmentClient.existsById(departmentUuid)).thenReturn(true);
        when(employeeCacheRepository.findByEmployeeId(approverEmployeeId)).thenReturn(Optional.of(
                EmployeeCache.builder().employeeId(approverEmployeeId).employmentStatus("Active").build()));
        when(departmentApproverRepository.findByDepartmentUuid(departmentUuid)).thenReturn(Optional.empty());
    }

    @Test
    void create_savesMapping_whenValid() {
        stubValid();
        when(departmentApproverRepository.save(any(DepartmentApprover.class))).thenAnswer(inv -> {
            DepartmentApprover d = inv.getArgument(0);
            d.setDepartmentApproverId(UUID.randomUUID());
            return d;
        });

        DepartmentApproverResponse response = service.create(validRequest());

        assertThat(response.departmentUuid()).isEqualTo(departmentUuid);
        assertThat(response.approverEmployeeId()).isEqualTo(approverEmployeeId);
    }

    @Test
    void create_throws_whenDepartmentDoesNotExist() {
        when(departmentClient.existsById(departmentUuid)).thenReturn(false);

        assertThatThrownBy(() -> service.create(validRequest())).isInstanceOf(IllegalArgumentException.class);
        verify(departmentApproverRepository, never()).save(any());
    }

    @Test
    void create_throws_whenApproverNotActive() {
        when(departmentClient.existsById(departmentUuid)).thenReturn(true);
        when(employeeCacheRepository.findByEmployeeId(approverEmployeeId)).thenReturn(Optional.of(
                EmployeeCache.builder().employeeId(approverEmployeeId).employmentStatus("Exited").build()));

        assertThatThrownBy(() -> service.create(validRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an Active employee");
    }

    @Test
    void create_throwsDuplicate_whenDepartmentAlreadyMapped() {
        when(departmentClient.existsById(departmentUuid)).thenReturn(true);
        when(employeeCacheRepository.findByEmployeeId(approverEmployeeId)).thenReturn(Optional.of(
                EmployeeCache.builder().employeeId(approverEmployeeId).employmentStatus("Active").build()));
        DepartmentApprover existing = DepartmentApprover.builder().departmentApproverId(UUID.randomUUID()).departmentUuid(departmentUuid).build();
        when(departmentApproverRepository.findByDepartmentUuid(departmentUuid)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(validRequest())).isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void getById_throws_whenMissing() {
        UUID id = UUID.randomUUID();
        when(departmentApproverRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_removesRow() {
        UUID id = UUID.randomUUID();
        DepartmentApprover existing = DepartmentApprover.builder().departmentApproverId(id).build();
        when(departmentApproverRepository.findById(id)).thenReturn(Optional.of(existing));

        service.delete(id);

        verify(departmentApproverRepository).delete(existing);
    }

    @Test
    void departmentOverview_listsEveryDepartment_withItsApprover_andOrphanedMappingsLast() {
        UUID finance = UUID.randomUUID();
        UUID removed = UUID.randomUUID();
        when(departmentClient.getAllDepartments()).thenReturn(List.of(
                new DepartmentResponse(departmentUuid, "Engineering", null),
                new DepartmentResponse(finance, "Finance", null)));
        when(departmentApproverRepository.findAll()).thenReturn(List.of(
                DepartmentApprover.builder().departmentApproverId(UUID.randomUUID()).departmentUuid(departmentUuid)
                        .approverEmployeeId(approverEmployeeId).status("ACTIVE").build(),
                DepartmentApprover.builder().departmentApproverId(UUID.randomUUID()).departmentUuid(removed)
                        .approverEmployeeId("5100099").status("ACTIVE").build()));
        when(employeeCacheRepository.findByEmployeeId(approverEmployeeId)).thenReturn(Optional.of(EmployeeCache.builder()
                .employeeId(approverEmployeeId).firstName("Ravi").lastName("Kumar").employmentStatus("Active").build()));
        when(employeeCacheRepository.findByEmployeeId("5100099")).thenReturn(Optional.empty());

        List<DepartmentApproverOverviewResponse> rows = service.getDepartmentOverview();

        assertThat(rows).extracting(DepartmentApproverOverviewResponse::departmentName)
                .containsExactly("Engineering", "Finance", null);
        assertThat(rows.get(0).approverName()).isEqualTo("Ravi Kumar");
        assertThat(rows.get(0).approverActive()).isTrue();
        assertThat(rows.get(1).departmentApproverId()).isNull();
        assertThat(rows.get(2).departmentInOnboarding()).isFalse();
    }

    @Test
    void departmentOverview_reportsOnboardingOutage_asIntegrationUnavailable() {
        when(departmentClient.getAllDepartments()).thenThrow(new org.springframework.web.client.ResourceAccessException("timeout"));

        assertThatThrownBy(() -> service.getDepartmentOverview()).isInstanceOf(IntegrationUnavailableException.class);
    }

    @Test
    void approverCandidates_matchUmsUsersToEmployeeRecords_byEmailThenUserId() {
        when(umsClient.getAllUsers()).thenReturn(List.of(
                new UmsUserResponse(UUID.randomUUID(), 1L, "Ravi", "Kumar", "Ravi.Kumar@paves.com", true),
                new UmsUserResponse(UUID.randomUUID(), 5100020L, "Asha", "Rao", "asha@other.com", true),
                new UmsUserResponse(UUID.randomUUID(), 7L, "No", "Record", "nobody@paves.com", true),
                new UmsUserResponse(UUID.randomUUID(), 8L, "Gone", "User", "gone@paves.com", false)));
        when(employeeCacheRepository.findAll()).thenReturn(List.of(
                EmployeeCache.builder().employeeId("5100014").workEmail("ravi.kumar@paves.com").employmentStatus("Active").build(),
                EmployeeCache.builder().employeeId("5100020").workEmail("asha@paves.com").employmentStatus("Exited").build()));

        List<ApproverCandidateResponse> candidates = service.getApproverCandidates();

        // Inactive UMS users are left out; selectable people first.
        assertThat(candidates).hasSize(3);
        assertThat(candidates.get(0).name()).isEqualTo("Ravi Kumar");
        assertThat(candidates.get(0).employeeId()).isEqualTo("5100014");
        assertThat(candidates.get(0).selectable()).isTrue();
        assertThat(candidates).filteredOn(c -> "Asha Rao".equals(c.name())).singleElement().satisfies(c -> {
            assertThat(c.employeeId()).isEqualTo("5100020");
            assertThat(c.selectable()).isFalse();
            assertThat(c.unavailableReason()).contains("Exited");
        });
        assertThat(candidates).filteredOn(c -> "No Record".equals(c.name())).singleElement().satisfies(c -> {
            assertThat(c.employeeId()).isNull();
            assertThat(c.selectable()).isFalse();
        });
    }
}
