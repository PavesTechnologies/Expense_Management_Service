package com.expense_management_service.e2e;

import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.CostCenterBudget;
import com.expense_management_service.entity.ExpenseCategory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Scenarios with a cash advance: request, approval chain (manager, cost center owner, Finance), disbursement,
 * the expense report against it, Finance's offset, AP payment of any excess, and settlement of an unused balance -
 * checking the advance balance, its status, the report's payment status and the cost center budget at each stage.
 */
class CashAdvanceWorkflowE2eTest extends E2eTestBase {

    private static final BigDecimal BUDGET = new BigDecimal("100000.00");

    /** One employee's cost center (with a budget), its owner and an expense category. */
    private record Setup(CostCenter costCenter, Persona costCenterOwner, Persona employee, ExpenseCategory category) {
    }

    private Setup setup() {
        Persona owner = new Persona("CCO" + suffix(), UUID.randomUUID(), 77L, List.of("GENERAL"));
        CostCenter costCenter = costCenter(owner.employeeId(), BUDGET);
        return new Setup(costCenter, owner, employee(costCenter), category());
    }

    private CostCenterBudget budget(CostCenter costCenter) {
        return costCenterBudgetRepository.findByCostCenter_CostCenterIdAndFiscalYear(costCenter.getCostCenterId(), currentFiscalYear())
                .orElseThrow();
    }

    private String advance(String advanceId) throws Exception {
        return getJson("/xms/employee/cash-advances/" + advanceId, FINANCE);
    }

    private String status(String advanceId) throws Exception {
        return read(advance(advanceId), "$.data.status");
    }

    /** Requests, submits and takes the advance through manager, cost center owner and Finance approval, then disburses it. */
    private String approvedAndDisbursedAdvance(Setup s, String amount) throws Exception {
        String created = postJson("/xms/employee/cash-advances", s.employee(), """
                {"amount":%s,"currencyId":"%s","costCenterId":"%s","purpose":"Client visit travel advance",
                 "title":"Mumbai trip","neededByDate":"%s"}
                """.formatted(amount, inr.getCurrencyId(), s.costCenter().getCostCenterId(), LocalDate.now().plusDays(20)), 201);
        String advanceId = read(created, "$.data.advanceId");
        assertThat((String) read(created, "$.data.status")).isEqualTo("DRAFT");

        call(post("/xms/employee/cash-advances/" + advanceId + "/submit"), s.employee(), 200);
        assertThat(status(advanceId)).isEqualTo("SUBMITTED");

        // The employee can't approve their own advance; the chain is manager -> cost center owner -> Finance.
        call(post("/xms/employee/cash-advances/" + advanceId + "/approve"), s.employee(), 422);
        call(post("/xms/employee/cash-advances/" + advanceId + "/approve"), MANAGER, 200);
        assertThat(status(advanceId)).isEqualTo("PENDING_COST_CENTER_APPROVAL");
        call(post("/xms/employee/cash-advances/" + advanceId + "/approve"), s.costCenterOwner(), 200);
        assertThat(status(advanceId)).isEqualTo("PENDING_FINANCE_APPROVAL");
        call(post("/xms/employee/cash-advances/" + advanceId + "/approve"), FINANCE, 200);
        assertThat(status(advanceId)).isEqualTo("APPROVED");

        // Approval alone is not a disbursement: the advance can't be expensed until Finance records the payment.
        call(post("/xms/employee/cash-advances/" + advanceId + "/disburse")
                .param("disbursementMode", "BANK_TRANSFER").param("transactionReference", "UTR-ADV-" + suffix())
                .param("transferDate", LocalDate.now().toString()).param("transferDetails", "HDFC ****1234"), s.employee(), 403);
        call(multipart("/xms/employee/cash-advances/" + advanceId + "/disburse")
                .param("disbursementMode", "BANK_TRANSFER").param("transactionReference", "UTR-ADV-" + suffix())
                .param("transferDate", LocalDate.now().toString()).param("transferDetails", "HDFC ****1234"), FINANCE, 200);
        assertThat(status(advanceId)).isEqualTo("IN_PROGRESS");
        return advanceId;
    }

    /** A report against the advance for {@code amount}, approved by the manager and verified by Finance. */
    private String approvedReportAgainst(Setup s, String advanceId, String amount) throws Exception {
        String reportId = createReport(s.employee(), s.costCenter(), UUID.fromString(advanceId));
        String lineItemId = addLine(s.employee(), reportId, s.category(), amount, null);
        submitReport(s.employee(), reportId);
        approveAndVerify(reportId, lineItemId);
        assertThat((String) read(report(reportId, s.employee()), "$.data.cashAdvanceId")).isEqualTo(advanceId);
        return reportId;
    }

    private String applyOffset(String reportId, String advanceId) throws Exception {
        return postJson("/xms/api/v1/expense-reports/" + reportId + "/apply-advance-offset", FINANCE,
                "{\"cashAdvanceId\":\"" + advanceId + "\"}", 200);
    }

    @Test
    void advance_approvedDisbursedAndOffset_leavesTheUnusedBalanceOpenWithNothingToPay() throws Exception {
        Setup s = setup();

        String advanceId = approvedAndDisbursedAdvance(s, "10000.00");
        // Final approval holds the principal against the cost center budget.
        CostCenterBudget afterApproval = budget(s.costCenter());
        assertThat(afterApproval.getAvailableBudget()).isEqualByComparingTo("90000.00");
        assertThat(afterApproval.getCashAdvanceAllocated()).isEqualByComparingTo("10000.00");

        String reportId = approvedReportAgainst(s, advanceId, "6000.00");
        // Expenses alone don't touch the advance: only Finance's offset does.
        assertThat(amount(advance(advanceId), "$.data.outstandingBalance")).isEqualByComparingTo("10000.00");

        String offset = applyOffset(reportId, advanceId);
        assertThat(amount(offset, "$.data.adjustedAmount")).isEqualByComparingTo("6000.00");

        String afterOffset = advance(advanceId);
        assertThat(amount(afterOffset, "$.data.outstandingBalance")).isEqualByComparingTo("4000.00");
        assertThat((String) read(afterOffset, "$.data.status")).isEqualTo("SETTLEMENT_PENDING");

        String settledReport = report(reportId, s.employee());
        assertThat(amount(settledReport, "$.data.reimbursableAmount")).isEqualByComparingTo("0");
        assertThat((Boolean) read(settledReport, "$.data.noPaymentDue")).isTrue();
        assertThat((String) read(settledReport, "$.data.paymentRoutingStatus")).isEqualTo("NONE");

        // The 6,000 became real spend; 4,000 is still held for the advance. Available is unchanged.
        CostCenterBudget afterOffsetBudget = budget(s.costCenter());
        assertThat(afterOffsetBudget.getCashAdvanceAllocated()).isEqualByComparingTo("4000.00");
        assertThat(afterOffsetBudget.getAvailableBudget()).isEqualByComparingTo("90000.00");

        String settlement = getJson("/xms/employee/cash-advances/" + advanceId + "/settlement", FINANCE);
        assertThat(amount(settlement, "$.data.totalAdjustedAmount")).isEqualByComparingTo("6000.00");
        assertThat(amount(settlement, "$.data.outstandingBalance")).isEqualByComparingTo("4000.00");

        // One report, one offset.
        postJson("/xms/api/v1/expense-reports/" + reportId + "/apply-advance-offset", FINANCE,
                "{\"cashAdvanceId\":\"" + advanceId + "\"}", 422);
    }

    @Test
    void expensesAboveTheAdvance_offsetTheWholeAdvance_andApPaysOnlyTheDifference() throws Exception {
        Setup s = setup();
        String advanceId = approvedAndDisbursedAdvance(s, "5000.00");
        String reportId = approvedReportAgainst(s, advanceId, "6000.00");

        String offset = applyOffset(reportId, advanceId);
        assertThat(amount(offset, "$.data.adjustedAmount")).isEqualByComparingTo("5000.00");

        String afterOffset = advance(advanceId);
        assertThat(amount(afterOffset, "$.data.outstandingBalance")).isEqualByComparingTo("0");
        assertThat((String) read(afterOffset, "$.data.status")).isEqualTo("CLOSED");

        String owed = report(reportId, s.employee());
        assertThat(amount(owed, "$.data.reimbursableAmount")).isEqualByComparingTo("1000.00");
        assertThat((String) read(owed, "$.data.paymentRoutingStatus")).isEqualTo("APPROVED_FOR_PAYMENT");

        // AP can't pay the gross: only the 1,000 above the advance is owed.
        postJson("/xms/ap-payments/" + reportId + "/complete", AP, """
                {"paymentReference":"UTR-GROSS","paymentMethod":"BANK_TRANSFER","paymentDate":"%s","amountPaid":6000.00}
                """.formatted(LocalDate.now()), 422);
        apCompletesPayment(reportId, "1000.00", "UTR-EXCESS");

        String paid = report(reportId, s.employee());
        assertThat((String) read(paid, "$.data.paymentRoutingStatus")).isEqualTo("PAYMENT_COMPLETED");
        assertThat(amount(paid, "$.data.paymentConfirmation.amountPaid")).isEqualByComparingTo("1000.00");

        // Budget: 5,000 advance spent via the offset, plus the 1,000 AP paid - 6,000 in all, charged once.
        CostCenterBudget budget = budget(s.costCenter());
        assertThat(budget.getCashAdvanceAllocated()).isEqualByComparingTo("0");
        assertThat(budget.getAvailableBudget()).isEqualByComparingTo("94000.00");
    }

    @Test
    void unusedBalance_staysOpenAcrossANewAdvance_untilFinanceRecordsTheSettlement() throws Exception {
        Setup s = setup();
        String firstAdvanceId = approvedAndDisbursedAdvance(s, "10000.00");
        String reportId = approvedReportAgainst(s, firstAdvanceId, "6000.00");
        applyOffset(reportId, firstAdvanceId);
        assertThat(amount(advance(firstAdvanceId), "$.data.outstandingBalance")).isEqualByComparingTo("4000.00");

        // The open balance doesn't block the next trip's advance (it isn't overdue).
        String secondAdvanceId = approvedAndDisbursedAdvance(s, "3000.00");
        // Each advance keeps its own balance: the 4,000 is not carried into the new one (no per-employee balance yet).
        assertThat(amount(advance(secondAdvanceId), "$.data.outstandingBalance")).isEqualByComparingTo("3000.00");
        assertThat(amount(advance(firstAdvanceId), "$.data.outstandingBalance")).isEqualByComparingTo("4000.00");
        assertThat(status(firstAdvanceId)).isEqualTo("SETTLEMENT_PENDING");
        assertThat(budget(s.costCenter()).getCashAdvanceAllocated()).isEqualByComparingTo("7000.00");

        // Finance settles the first advance's unused balance; only Finance can record it.
        String repayment = """
                {"advanceId":"%s","amount":4000.00,"paymentMethod":"BANK_TRANSFER","paymentReference":"RPY-%s","notes":"Unused balance returned"}
                """.formatted(firstAdvanceId, suffix());
        postJson("/xms/employee/cash-advances/" + firstAdvanceId + "/repayments", s.employee(), repayment, 403);
        postJson("/xms/employee/cash-advances/" + firstAdvanceId + "/repayments", FINANCE, repayment, 200);

        String settled = advance(firstAdvanceId);
        assertThat(amount(settled, "$.data.outstandingBalance")).isEqualByComparingTo("0");
        assertThat((String) read(settled, "$.data.status")).isEqualTo("CLOSED");
        String settlement = getJson("/xms/employee/cash-advances/" + firstAdvanceId + "/settlement", FINANCE);
        assertThat(amount(settlement, "$.data.totalAdjustedAmount")).isEqualByComparingTo("6000.00");
        assertThat(amount(settlement, "$.data.totalRepaidAmount")).isEqualByComparingTo("4000.00");
        assertThat((String) read(settlement, "$.data.settlementStatus")).isEqualTo("FULLY_SETTLED");

        // The repaid 4,000 goes back to the budget; the second advance's 3,000 is still held.
        CostCenterBudget budget = budget(s.costCenter());
        assertThat(budget.getCashAdvanceAllocated()).isEqualByComparingTo("3000.00");
        assertThat(budget.getAvailableBudget()).isEqualByComparingTo("91000.00");
        assertThat(status(secondAdvanceId)).isEqualTo("IN_PROGRESS");
    }
}
