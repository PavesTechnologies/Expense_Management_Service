package com.expense_management_service.service.impl;

import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.ExpenseSplit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Rs 500 restaurant bill paid by one employee for an Engineering + HR event, split 250 / 250. */
class ReportCostCenterSharesTest {

    private final CostCenter engineering = costCenter("ENG");
    private final CostCenter hr = costCenter("HR");
    private final CostCenter finance = costCenter("FIN");

    @Test
    void splitLine_isAttributedToEachSplitsCostCenter() {
        ExpenseReport report = report(engineering, splitLine("500.00", split(engineering, "250.00"), split(hr, "250.00")));

        List<ReportCostCenterShares.Share> shares = ReportCostCenterShares.of(report);

        assertThat(shares).extracting(s -> s.costCenter().getCostCenterCode()).containsExactly("ENG", "HR");
        assertThat(shares).extracting(ReportCostCenterShares.Share::amount)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("250.00"), new BigDecimal("250.00"));
    }

    @Test
    void unsplitLines_useTheirOwnCostCenter_elseTheReports_andMergePerCostCenter() {
        ExpenseReport report = report(engineering,
                line("100.00", hr), line("40.00", null), line("60.00", engineering));

        List<ReportCostCenterShares.Share> shares = ReportCostCenterShares.of(report);

        assertThat(shares).extracting(s -> s.costCenter().getCostCenterCode()).containsExactly("HR", "ENG");
        assertThat(shares).extracting(ReportCostCenterShares.Share::amount)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("100.00"), new BigDecimal("100.00"));
    }

    @Test
    void removedSplit_isIgnored() {
        ExpenseSplit removed = split(finance, "100.00");
        removed.setRemovedAt(LocalDateTime.now());
        ExpenseReport report = report(engineering, splitLine("500.00", split(engineering, "250.00"), split(hr, "250.00"), removed));

        assertThat(ReportCostCenterShares.of(report)).extracting(s -> s.costCenter().getCostCenterCode()).containsExactly("ENG", "HR");
    }

    @Test
    void distribute_proRatesAPartialAmount_overTheSplit() {
        // Only Rs 200 of the Rs 500 is still payable after a cash-advance offset.
        ExpenseReport report = report(engineering, splitLine("500.00", split(engineering, "250.00"), split(hr, "250.00")));

        List<ReportCostCenterShares.Share> shares = ReportCostCenterShares.distribute(new BigDecimal("200.00"), report);

        assertThat(shares).extracting(ReportCostCenterShares.Share::amount)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("100.00"), new BigDecimal("100.00"));
    }

    @Test
    void distribute_putsTheRoundingRemainderOnTheLastShare_soThePartsAddUpExactly() {
        ExpenseReport report = report(engineering, splitLine("300.00",
                split(engineering, "100.00"), split(hr, "100.00"), split(finance, "100.00")));

        List<ReportCostCenterShares.Share> shares = ReportCostCenterShares.distribute(new BigDecimal("100.00"), report);

        assertThat(shares).extracting(ReportCostCenterShares.Share::amount).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("33.33"), new BigDecimal("33.33"), new BigDecimal("33.34"));
    }

    @Test
    void distribute_withNoAttributableLines_chargesTheReportsCostCenter() {
        ExpenseReport report = report(engineering);

        List<ReportCostCenterShares.Share> shares = ReportCostCenterShares.distribute(new BigDecimal("12500.00"), report);

        assertThat(shares).singleElement().satisfies(share -> {
            assertThat(share.costCenter()).isSameAs(engineering);
            assertThat(share.amount()).isEqualByComparingTo("12500.00");
        });
    }

    @Test
    void distribute_ofNothing_isEmpty() {
        ExpenseReport report = report(engineering, line("500.00", hr));

        assertThat(ReportCostCenterShares.distribute(BigDecimal.ZERO, report)).isEmpty();
        assertThat(ReportCostCenterShares.distribute(null, report)).isEmpty();
    }

    private static CostCenter costCenter(String code) {
        return CostCenter.builder().costCenterId(UUID.randomUUID()).costCenterCode(code).build();
    }

    private static ExpenseReport report(CostCenter costCenter, ExpenseLineItem... lines) {
        return ExpenseReport.builder().reportId(UUID.randomUUID()).costCenter(costCenter)
                .expenseLineItems(new ArrayList<>(List.of(lines))).build();
    }

    private static ExpenseLineItem line(String baseAmount, CostCenter costCenter) {
        return ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).baseAmount(new BigDecimal(baseAmount))
                .costCenter(costCenter).expenseSplits(new ArrayList<>()).build();
    }

    private static ExpenseLineItem splitLine(String baseAmount, ExpenseSplit... splits) {
        ExpenseLineItem line = line(baseAmount, null);
        line.getExpenseSplits().addAll(List.of(splits));
        return line;
    }

    private static ExpenseSplit split(CostCenter costCenter, String allocatedAmount) {
        return ExpenseSplit.builder().splitId(UUID.randomUUID()).costCenter(costCenter)
                .allocatedAmount(new BigDecimal(allocatedAmount)).build();
    }
}
