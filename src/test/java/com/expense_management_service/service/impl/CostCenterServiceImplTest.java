
package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.CostCenterRequest;
import com.expense_management_service.dto.response.CostCenterResponse;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.integration.departments.DepartmentClient;
import com.expense_management_service.mapper.CostCenterMapper;
import com.expense_management_service.repository.CostCenterRepository;
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
class CostCenterServiceImplTest {

    @Mock
    private CostCenterRepository costCenterRepository;

    @Mock
    private DepartmentClient departmentClient;

    @Mock
    private EmployeeCacheRepository employeeCacheRepository;

    private CostCenterServiceImpl costCenterService;

    private UUID departmentUuid;
    private String ownerEmployeeId;

    @BeforeEach
    void setUp() {
        costCenterService = new CostCenterServiceImpl(
                costCenterRepository, new CostCenterMapper(), departmentClient, employeeCacheRepository);
        departmentUuid = UUID.randomUUID();
        ownerEmployeeId = "5100014";
    }

    private CostCenterRequest validRequest() {
        return new CostCenterRequest("CC-100", "Backend Development", departmentUuid, "desc", ownerEmployeeId, "ACTIVE");
    }

    private void stubDepartmentAndOwnerValid() {
        when(departmentClient.existsById(departmentUuid)).thenReturn(true);
        when(employeeCacheRepository.findByEmployeeId(ownerEmployeeId)).thenReturn(Optional.of(
                EmployeeCache.builder().employeeId(ownerEmployeeId).employmentStatus("Active").build()));
    }

    @Test
    void create_savesNewCostCenter_whenValid() {
        stubDepartmentAndOwnerValid();
        when(costCenterRepository.findByCostCenterCodeIgnoreCase("CC-100")).thenReturn(Optional.empty());
        when(costCenterRepository.findByCostCenterNameIgnoreCaseAndDepartmentUuid("Backend Development", departmentUuid))
                .thenReturn(Optional.empty());
        when(costCenterRepository.save(any(CostCenter.class))).thenAnswer(invocation -> {
            CostCenter saved = invocation.getArgument(0);
            saved.setCostCenterId(UUID.randomUUID());
            return saved;
        });

        CostCenterResponse response = costCenterService.create(validRequest());

        assertThat(response.costCenterCode()).isEqualTo("CC-100");
        assertThat(response.departmentUuid()).isEqualTo(departmentUuid);
        assertThat(response.ownerEmployeeId()).isEqualTo(ownerEmployeeId);
        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    void create_throwsIllegalArgumentException_whenDepartmentDoesNotExist() {
        when(departmentClient.existsById(departmentUuid)).thenReturn(false);

        assertThatThrownBy(() -> costCenterService.create(validRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("departmentUuid");

        verify(costCenterRepository, never()).save(any());
    }

    @Test
    void create_throwsIllegalArgumentException_whenOwnerDoesNotExist() {
        when(departmentClient.existsById(departmentUuid)).thenReturn(true);
        when(employeeCacheRepository.findByEmployeeId(ownerEmployeeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> costCenterService.create(validRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ownerEmployeeId does not exist");

        verify(costCenterRepository, never()).save(any());
    }

    @Test
    void create_throwsIllegalArgumentException_whenOwnerIsInactive() {
        when(departmentClient.existsById(departmentUuid)).thenReturn(true);
        when(employeeCacheRepository.findByEmployeeId(ownerEmployeeId)).thenReturn(Optional.of(
                EmployeeCache.builder().employeeId(ownerEmployeeId).employmentStatus("Exited").build()));

        assertThatThrownBy(() -> costCenterService.create(validRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not Active");

        verify(costCenterRepository, never()).save(any());
    }

    @Test
    void create_throwsDuplicateResourceException_whenCodeAlreadyExists() {
        stubDepartmentAndOwnerValid();
        CostCenter existing = CostCenter.builder().costCenterId(UUID.randomUUID()).costCenterCode("CC-100").build();
        when(costCenterRepository.findByCostCenterCodeIgnoreCase("CC-100")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> costCenterService.create(validRequest()))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("CC-100");

        verify(costCenterRepository, never()).save(any());
    }

    @Test
    void create_throwsDuplicateResourceException_whenNameAlreadyExistsWithinSameDepartment() {
        stubDepartmentAndOwnerValid();
        when(costCenterRepository.findByCostCenterCodeIgnoreCase("CC-100")).thenReturn(Optional.empty());
        CostCenter existing = CostCenter.builder().costCenterId(UUID.randomUUID())
                .costCenterName("Backend Development").departmentUuid(departmentUuid).build();
        when(costCenterRepository.findByCostCenterNameIgnoreCaseAndDepartmentUuid("Backend Development", departmentUuid))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> costCenterService.create(validRequest()))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Backend Development");

        verify(costCenterRepository, never()).save(any());
    }

    @Test
    void update_allowsCostCenterToKeepItsOwnCodeAndName() {
        UUID id = UUID.randomUUID();
        CostCenter existing = CostCenter.builder()
                .costCenterId(id).costCenterCode("CC-100").costCenterName("Backend Development")
                .departmentUuid(departmentUuid).status("ACTIVE").build();

        when(costCenterRepository.findById(id)).thenReturn(Optional.of(existing));
        stubDepartmentAndOwnerValid();
        when(costCenterRepository.findByCostCenterCodeIgnoreCase("CC-100")).thenReturn(Optional.of(existing));
        when(costCenterRepository.findByCostCenterNameIgnoreCaseAndDepartmentUuid("Backend Development", departmentUuid))
                .thenReturn(Optional.of(existing));
        when(costCenterRepository.save(any(CostCenter.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CostCenterResponse response = costCenterService.update(id, validRequest());

        assertThat(response.costCenterName()).isEqualTo("Backend Development");
    }

    @Test
    void update_throwsDuplicateResourceException_whenNameBelongsToAnotherCostCenterInSameDepartment() {
        UUID id = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        CostCenter existing = CostCenter.builder()
                .costCenterId(id).costCenterCode("CC-100").costCenterName("Old Name")
                .departmentUuid(departmentUuid).status("ACTIVE").build();
        CostCenter other = CostCenter.builder().costCenterId(otherId).costCenterName("Backend Development")
                .departmentUuid(departmentUuid).build();

        when(costCenterRepository.findById(id)).thenReturn(Optional.of(existing));
        stubDepartmentAndOwnerValid();
        when(costCenterRepository.findByCostCenterCodeIgnoreCase("CC-100")).thenReturn(Optional.of(existing));
        when(costCenterRepository.findByCostCenterNameIgnoreCaseAndDepartmentUuid("Backend Development", departmentUuid))
                .thenReturn(Optional.of(other));

        assertThatThrownBy(() -> costCenterService.update(id, validRequest()))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void getById_throwsResourceNotFoundException_whenMissing() {
        UUID id = UUID.randomUUID();
        when(costCenterRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> costCenterService.getById(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getAll_returnsAllCostCenters() {
        CostCenter costCenter = CostCenter.builder()
                .costCenterId(UUID.randomUUID()).costCenterCode("CC-100").costCenterName("Backend Development")
                .departmentUuid(departmentUuid).status("ACTIVE").build();
        when(costCenterRepository.findAll()).thenReturn(List.of(costCenter));

        assertThat(costCenterService.getAll()).hasSize(1);
    }

    @Test
    void delete_softDeletesByMarkingInactive_insteadOfRemovingTheRow() {
        UUID id = UUID.randomUUID();
        CostCenter existing = CostCenter.builder().costCenterId(id).status("ACTIVE").build();
        when(costCenterRepository.findById(id)).thenReturn(Optional.of(existing));
        when(costCenterRepository.save(any(CostCenter.class))).thenAnswer(invocation -> invocation.getArgument(0));

        costCenterService.delete(id);

        assertThat(existing.getStatus()).isEqualTo("INACTIVE");
        verify(costCenterRepository).save(existing);
        verify(costCenterRepository, never()).delete(any());
    }
}
