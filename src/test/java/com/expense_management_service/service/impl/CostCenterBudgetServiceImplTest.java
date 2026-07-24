package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.CostCenterBudgetRequest;
import com.expense_management_service.dto.response.CostCenterBudgetResponse;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.CostCenterBudget;
import com.expense_management_service.mapper.CostCenterBudgetMapper;
import com.expense_management_service.repository.CostCenterBudgetRepository;
import com.expense_management_service.repository.CostCenterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
class CostCenterBudgetServiceImplTest {

    @Mock
    private CostCenterBudgetRepository costCenterBudgetRepository;

    @Mock
    private CostCenterRepository costCenterRepository;

    private CostCenterBudgetServiceImpl costCenterBudgetService;

    private UUID costCenterId;
    private CostCenter activeCostCenter;

    @BeforeEach
    void setUp() {
        costCenterBudgetService = new CostCenterBudgetServiceImpl(
                costCenterBudgetRepository, costCenterRepository, new CostCenterBudgetMapper());
        costCenterId = UUID.randomUUID();
        activeCostCenter = CostCenter.builder()
                .costCenterId(costCenterId).costCenterCode("CC-100").costCenterName("Backend Development")
                .status("ACTIVE").build();
    }

    private CostCenterBudgetRequest requestWithAvailable(BigDecimal budgetAmount, BigDecimal availableBudget) {
        return new CostCenterBudgetRequest(costCenterId, "FY2026", budgetAmount, availableBudget);
    }

    @Test
    void create_savesNewBudget_whenAvailableBudgetExplicitlySupplied() {
        when(costCenterRepository.findById(costCenterId)).thenReturn(Optional.of(activeCostCenter));
        when(costCenterBudgetRepository.findByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterId, "FY2026"))
                .thenReturn(Optional.empty());
        when(costCenterBudgetRepository.save(any(CostCenterBudget.class))).thenAnswer(invocation -> {
            CostCenterBudget saved = invocation.getArgument(0);
            saved.setBudgetId(UUID.randomUUID());
            return saved;
        });

        CostCenterBudgetResponse response = costCenterBudgetService.create(
                requestWithAvailable(BigDecimal.valueOf(10000), BigDecimal.valueOf(6000)));

        assertThat(response.budgetAmount()).isEqualByComparingTo("10000");
        assertThat(response.availableBudget()).isEqualByComparingTo("6000");
        assertThat(response.costCenterName()).isEqualTo("Backend Development");
    }

    @Test
    void create_defaultsAvailableBudgetToBudgetAmount_whenNotSupplied() {
        when(costCenterRepository.findById(costCenterId)).thenReturn(Optional.of(activeCostCenter));
        when(costCenterBudgetRepository.findByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterId, "FY2026"))
                .thenReturn(Optional.empty());
        when(costCenterBudgetRepository.save(any(CostCenterBudget.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CostCenterBudgetResponse response = costCenterBudgetService.create(
                requestWithAvailable(BigDecimal.valueOf(10000), null));

        assertThat(response.availableBudget()).isEqualByComparingTo("10000");
    }

    @Test
    void create_throwsResourceNotFoundException_whenCostCenterMissing() {
        when(costCenterRepository.findById(costCenterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> costCenterBudgetService.create(requestWithAvailable(BigDecimal.TEN, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(costCenterBudgetRepository, never()).save(any());
    }

    @Test
    void create_throwsIllegalArgumentException_whenCostCenterIsNotActive() {
        CostCenter inactive = CostCenter.builder().costCenterId(costCenterId).costCenterCode("CC-100").status("INACTIVE").build();
        when(costCenterRepository.findById(costCenterId)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> costCenterBudgetService.create(requestWithAvailable(BigDecimal.TEN, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not Active");

        verify(costCenterBudgetRepository, never()).save(any());
    }

    @Test
    void create_throwsIllegalArgumentException_whenAvailableBudgetIsNegative() {
        when(costCenterRepository.findById(costCenterId)).thenReturn(Optional.of(activeCostCenter));
        when(costCenterBudgetRepository.findByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterId, "FY2026"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> costCenterBudgetService.create(
                requestWithAvailable(BigDecimal.valueOf(1000), BigDecimal.valueOf(-1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");

        verify(costCenterBudgetRepository, never()).save(any());
    }

    @Test
    void create_throwsIllegalArgumentException_whenAvailableBudgetExceedsBudgetAmount() {
        when(costCenterRepository.findById(costCenterId)).thenReturn(Optional.of(activeCostCenter));
        when(costCenterBudgetRepository.findByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterId, "FY2026"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> costCenterBudgetService.create(
                requestWithAvailable(BigDecimal.valueOf(1000), BigDecimal.valueOf(1500))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed");

        verify(costCenterBudgetRepository, never()).save(any());
    }

    @Test
    void create_throwsDuplicateResourceException_whenFiscalYearAlreadyExistsForCostCenter() {
        when(costCenterRepository.findById(costCenterId)).thenReturn(Optional.of(activeCostCenter));
        CostCenterBudget existing = CostCenterBudget.builder().budgetId(UUID.randomUUID()).fiscalYear("FY2026").build();
        when(costCenterBudgetRepository.findByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterId, "FY2026"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> costCenterBudgetService.create(requestWithAvailable(BigDecimal.TEN, null)))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("FY2026");

        verify(costCenterBudgetRepository, never()).save(any());
    }

    @Test
    void update_allowsBudgetToKeepItsOwnFiscalYear() {
        UUID budgetId = UUID.randomUUID();
        CostCenterBudget existing = CostCenterBudget.builder()
                .budgetId(budgetId).costCenter(activeCostCenter).fiscalYear("FY2026")
                .budgetAmount(BigDecimal.valueOf(10000)).availableBudget(BigDecimal.valueOf(8000)).build();

        when(costCenterBudgetRepository.findById(budgetId)).thenReturn(Optional.of(existing));
        when(costCenterRepository.findById(costCenterId)).thenReturn(Optional.of(activeCostCenter));
        when(costCenterBudgetRepository.findByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterId, "FY2026"))
                .thenReturn(Optional.of(existing));
        when(costCenterBudgetRepository.save(any(CostCenterBudget.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CostCenterBudgetResponse response = costCenterBudgetService.update(
                budgetId, requestWithAvailable(BigDecimal.valueOf(12000), BigDecimal.valueOf(9000)));

        assertThat(response.budgetAmount()).isEqualByComparingTo("12000");
        assertThat(response.availableBudget()).isEqualByComparingTo("9000");
    }

    @Test
    void update_keepsExistingAvailableBudget_whenNotSuppliedInRequest() {
        UUID budgetId = UUID.randomUUID();
        CostCenterBudget existing = CostCenterBudget.builder()
                .budgetId(budgetId).costCenter(activeCostCenter).fiscalYear("FY2026")
                .budgetAmount(BigDecimal.valueOf(10000)).availableBudget(BigDecimal.valueOf(7000)).build();

        when(costCenterBudgetRepository.findById(budgetId)).thenReturn(Optional.of(existing));
        when(costCenterRepository.findById(costCenterId)).thenReturn(Optional.of(activeCostCenter));
        when(costCenterBudgetRepository.findByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterId, "FY2026"))
                .thenReturn(Optional.of(existing));
        when(costCenterBudgetRepository.save(any(CostCenterBudget.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CostCenterBudgetResponse response = costCenterBudgetService.update(
                budgetId, requestWithAvailable(BigDecimal.valueOf(10000), null));

        assertThat(response.availableBudget()).isEqualByComparingTo("7000");
    }

    @Test
    void update_throwsDuplicateResourceException_whenFiscalYearBelongsToAnotherBudgetForSameCostCenter() {
        UUID budgetId = UUID.randomUUID();
        UUID otherBudgetId = UUID.randomUUID();
        CostCenterBudget existing = CostCenterBudget.builder()
                .budgetId(budgetId).costCenter(activeCostCenter).fiscalYear("FY2025")
                .budgetAmount(BigDecimal.valueOf(10000)).availableBudget(BigDecimal.valueOf(10000)).build();
        CostCenterBudget other = CostCenterBudget.builder().budgetId(otherBudgetId).fiscalYear("FY2026").build();

        when(costCenterBudgetRepository.findById(budgetId)).thenReturn(Optional.of(existing));
        when(costCenterRepository.findById(costCenterId)).thenReturn(Optional.of(activeCostCenter));
        when(costCenterBudgetRepository.findByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterId, "FY2026"))
                .thenReturn(Optional.of(other));

        assertThatThrownBy(() -> costCenterBudgetService.update(budgetId, requestWithAvailable(BigDecimal.TEN, null)))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void getById_throwsResourceNotFoundException_whenMissing() {
        UUID budgetId = UUID.randomUUID();
        when(costCenterBudgetRepository.findById(budgetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> costCenterBudgetService.getById(budgetId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getAll_returnsAllBudgets() {
        CostCenterBudget budget = CostCenterBudget.builder()
                .budgetId(UUID.randomUUID()).costCenter(activeCostCenter).fiscalYear("FY2026")
                .budgetAmount(BigDecimal.TEN).availableBudget(BigDecimal.TEN).build();
        when(costCenterBudgetRepository.findAll()).thenReturn(List.of(budget));

        assertThat(costCenterBudgetService.getAll()).hasSize(1);
    }

    @Test
    void delete_removesExistingBudget() {
        UUID budgetId = UUID.randomUUID();
        CostCenterBudget existing = CostCenterBudget.builder().budgetId(budgetId).build();
        when(costCenterBudgetRepository.findById(budgetId)).thenReturn(Optional.of(existing));

        costCenterBudgetService.delete(budgetId);

        verify(costCenterBudgetRepository).delete(existing);
    }
}
