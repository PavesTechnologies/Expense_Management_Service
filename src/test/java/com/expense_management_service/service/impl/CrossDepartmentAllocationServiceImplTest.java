package com.expense_management_service.service.impl;

import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.ExpenseSplit;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.repository.ExpenseSplitRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** One employee pays for an Engineering + HR event and splits it; allocation follows the event, not the payer's department. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrossDepartmentAllocationServiceImplTest {

    private static final UUID ENGINEERING = UUID.randomUUID();
    private static final UUID HR = UUID.randomUUID();

    @Mock
    private EmployeeCacheRepository employeeCacheRepository;
    @Mock
    private ExpenseSplitRepository expenseSplitRepository;
    @Mock
    private CurrentUserService currentUserService;

    private CrossDepartmentAllocationServiceImpl service;

    private final CostCenter engineeringCc = costCenter("Engineering Ops", ENGINEERING);
    private final CostCenter hrCc = costCenter("HR Programs", HR);
    private ExpenseReport report;

    @BeforeEach
    void setUp() {
        service = new CrossDepartmentAllocationServiceImpl(employeeCacheRepository, expenseSplitRepository, currentUserService);
        // The payer works in Engineering.
        when(employeeCacheRepository.findByEmployeeId("alice"))
                .thenReturn(Optional.of(EmployeeCache.builder().employeeId("alice").departmentUuid(ENGINEERING.toString()).build()));
        when(expenseSplitRepository.findByLineItem_LineItemIdAndRemovedAtIsNullOrderBySplitOrderAsc(any())).thenReturn(List.of());
        report = ExpenseReport.builder().reportId(UUID.randomUUID()).employeeId("alice").costCenter(engineeringCc)
                .expenseLineItems(new ArrayList<>()).build();
    }

    @Test
    void ownDepartmentCostCenter_needsNoReason() {
        report.getExpenseLineItems().add(line("Team lunch", engineeringCc, null));

        assertThat(service.findLinesMissingReason(report)).isEmpty();
    }

    @Test
    void otherDepartmentCostCenter_withoutReason_isReported() {
        report.getExpenseLineItems().add(line("Hiring fair stall", hrCc, null));

        assertThat(service.findLinesMissingReason(report)).singleElement().asString()
                .contains("'Hiring fair stall'")
                .contains("HR Programs")
                .contains("outside your department");
    }

    @Test
    void otherDepartmentCostCenter_withReason_isAllowed() {
        report.getExpenseLineItems().add(line("Hiring fair stall", hrCc, "Joint Eng-HR campus hiring event"));

        assertThat(service.findLinesMissingReason(report)).isEmpty();
    }

    @Test
    void splitAcrossEngineeringAndHr_needsAReason_forTheHrShare() {
        // Rs 500 restaurant bill: Rs 250 Engineering, Rs 250 HR.
        ExpenseLineItem lunch = line("Spice Route", engineeringCc, null);
        report.getExpenseLineItems().add(lunch);
        when(expenseSplitRepository.findByLineItem_LineItemIdAndRemovedAtIsNullOrderBySplitOrderAsc(lunch.getLineItemId()))
                .thenReturn(List.of(split(lunch, engineeringCc), split(lunch, hrCc)));

        assertThat(service.findLinesMissingReason(report)).singleElement().asString()
                .contains("HR Programs")
                .doesNotContain("Engineering Ops");
    }

    @Test
    void splitsReplaceTheLinesOwnCostCenter() {
        // Line header points at HR, but every split share is Engineering - nothing lands outside.
        ExpenseLineItem line = line("Offsite", hrCc, null);
        report.getExpenseLineItems().add(line);
        when(expenseSplitRepository.findByLineItem_LineItemIdAndRemovedAtIsNullOrderBySplitOrderAsc(line.getLineItemId()))
                .thenReturn(List.of(split(line, engineeringCc), split(line, costCenter("Engineering R&D", ENGINEERING))));

        assertThat(service.findLinesMissingReason(report)).isEmpty();
    }

    @Test
    void lineWithoutItsOwnCostCenter_usesTheReports() {
        report.setCostCenter(hrCc);
        report.getExpenseLineItems().add(line("Snacks", null, null));

        assertThat(service.findLinesMissingReason(report)).singleElement().asString().contains("HR Programs");
    }

    @Test
    void unknownHomeDepartment_requiresNothing() {
        when(employeeCacheRepository.findByEmployeeId("alice")).thenReturn(Optional.empty());
        report.getExpenseLineItems().add(line("Hiring fair stall", hrCc, null));

        assertThat(service.findLinesMissingReason(report)).isEmpty();
    }

    @Test
    void unknownHomeDepartment_splitAcrossTwoDepartments_stillNeedsAReason() {
        when(employeeCacheRepository.findByEmployeeId("alice")).thenReturn(Optional.empty());
        ExpenseLineItem lunch = line("Spice Route", engineeringCc, null);
        report.getExpenseLineItems().add(lunch);
        when(expenseSplitRepository.findByLineItem_LineItemIdAndRemovedAtIsNullOrderBySplitOrderAsc(lunch.getLineItemId()))
                .thenReturn(List.of(split(lunch, engineeringCc), split(lunch, hrCc)));

        assertThat(service.findLinesMissingReason(report)).singleElement().asString()
                .contains("'Spice Route'")
                .contains("Engineering Ops")
                .contains("HR Programs")
                .contains("different departments");
    }

    @Test
    void unknownHomeDepartment_splitWithinOneDepartment_needsNoReason() {
        when(employeeCacheRepository.findByEmployeeId("alice")).thenReturn(Optional.empty());
        ExpenseLineItem offsite = line("Offsite", engineeringCc, null);
        report.getExpenseLineItems().add(offsite);
        when(expenseSplitRepository.findByLineItem_LineItemIdAndRemovedAtIsNullOrderBySplitOrderAsc(offsite.getLineItemId()))
                .thenReturn(List.of(split(offsite, engineeringCc), split(offsite, costCenter("Engineering R&D", ENGINEERING))));

        assertThat(service.findLinesMissingReason(report)).isEmpty();
    }

    @Test
    void costCenterWithoutADepartment_isNotTreatedAsCrossDepartment() {
        report.getExpenseLineItems().add(line("Shared services", costCenter("Corporate", null), null));

        assertThat(service.findLinesMissingReason(report)).isEmpty();
    }

    @Test
    void allocationContext_isTheCallersHomeDepartment() {
        when(currentUserService.getCurrentUser())
                .thenReturn(new CurrentUser(UUID.randomUUID(), null, "alice", "a@x.com", "Alice", List.of("GENERAL"), List.of()));

        assertThat(service.getAllocationContextForCurrentUser().homeDepartmentUuid()).isEqualTo(ENGINEERING.toString());
    }

    private static CostCenter costCenter(String name, UUID department) {
        return CostCenter.builder().costCenterId(UUID.randomUUID()).costCenterName(name).departmentUuid(department).build();
    }

    private ExpenseLineItem line(String merchant, CostCenter costCenter, String reason) {
        return ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).report(report).merchantName(merchant)
                .expenseDate(LocalDate.of(2026, 10, 10)).costCenter(costCenter).allocationReason(reason).build();
    }

    private static ExpenseSplit split(ExpenseLineItem line, CostCenter costCenter) {
        return ExpenseSplit.builder().splitId(UUID.randomUUID()).lineItem(line).costCenter(costCenter).build();
    }
}
