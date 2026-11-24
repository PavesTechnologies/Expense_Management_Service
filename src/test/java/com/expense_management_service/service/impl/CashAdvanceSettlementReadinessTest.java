package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.dto.response.CashAdvanceUtilizationResponse;
import com.expense_management_service.dto.response.CashAdvanceUtilizationResponse.LinkedReport;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CashAdvanceSettlementReadinessTest {

    private final CashAdvanceSettlementReadiness readiness =
            new CashAdvanceSettlementReadiness(mock(CashAdvanceUtilizationCalculator.class));

    /** INR 4,000 paid; {@code inApproval} claimed on report EXP-2 (pending approval); {@code approved} set against it. */
    private static CashAdvanceUtilizationResponse utilization(String approved, String inApproval, String settleable) {
        return new CashAdvanceUtilizationResponse(UUID.randomUUID(), "INR", new BigDecimal("4000"),
                new BigDecimal(inApproval), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal(approved), BigDecimal.ZERO, 0, new BigDecimal("4000"), true, "IN_PROGRESS",
                "REPAYMENT_DUE", false, BigDecimal.ZERO,
                List.of(new LinkedReport(UUID.randomUUID(), "EXP-1", "APPROVED", BigDecimal.TEN, BigDecimal.ZERO),
                        new LinkedReport(UUID.randomUUID(), "EXP-2", "PENDING_APPROVAL", new BigDecimal(inApproval), BigDecimal.ZERO)),
                List.of(), new BigDecimal(inApproval), new BigDecimal(settleable));
    }

    @Test
    void allowsUpToTheBalanceTheExpensesInApprovalDoNotCover() {
        var u = utilization("0", "3450", "550");

        assertThatCode(() -> readiness.assertWithinSettleable(u, new BigDecimal("550"), "carried forward"))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesMoreThanThat_andNamesTheHeldAmountAndReport() {
        var u = utilization("0", "3450", "550");

        assertThatThrownBy(() -> readiness.assertWithinSettleable(u, new BigDecimal("4000"), "carried forward"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only INR 550.00 can be carried forward now")
                .hasMessageContaining("INR 3450.00 of the balance is held for expenses still in approval on EXP-2 (Pending approval)")
                .hasMessageNotContaining("EXP-1");
    }

    @Test
    void anExpenseInApprovalCountsAsALinkedExpense() {
        assertThatCode(() -> readiness.assertHasLinkedExpense(utilization("0", "3450", "550"), "settling it"))
                .doesNotThrowAnyException();
        assertThatCode(() -> readiness.assertHasLinkedExpense(utilization("500", "0", "3500"), "settling it"))
                .doesNotThrowAnyException();
    }

    @Test
    void noExpenseAtAll_isRefused() {
        assertThatThrownBy(() -> readiness.assertHasLinkedExpense(utilization("0", "0", "4000"), "settling it"))
                .hasMessageContaining("Link at least one expense to this advance before settling it");
    }
}
