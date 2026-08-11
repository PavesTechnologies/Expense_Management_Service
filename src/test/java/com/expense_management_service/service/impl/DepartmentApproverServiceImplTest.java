package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.DepartmentApproverRequest;
import com.expense_management_service.dto.response.DepartmentApproverResponse;
import com.expense_management_service.entity.DepartmentApprover;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.integration.departments.DepartmentClient;
import com.expense_management_service.mapper.DepartmentApproverMapper;
import com.expense_management_service.repository.DepartmentApproverRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private DepartmentApproverServiceImpl service;

    private UUID departmentUuid;
    private String approverEmployeeId;

    @BeforeEach
    void setUp() {
        service = new DepartmentApproverServiceImpl(departmentApproverRepository, new DepartmentApproverMapper(), departmentClient, employeeCacheRepository);
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
}
