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

    /** Canonical repayment contract: multipart form with the bank-transfer confirmation (a fresh builder per call). */
    private static org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder repaymentForm(
            String advanceId, String reference) {
        return multipart("/xms/employee/cash-advances/" + advanceId + "/repayments")
                .param("amount", "4000.00")
                .param("notes", "Unused balance returned")
                .param("disbursementMode", "BANK_TRANSFER")
                .param("transactionReference", reference)
                .param("transferDate", LocalDate.now().toString())
                .param("transferDetails", "HDFC A/C 1234");
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

    /**
     * Approval reconciles the report against its advance automatically: the approved 6000 is recorded against the
     * 10000 advance, leaving 4000 outstanding. No separate Finance step is needed, and the manual offset endpoint stays gone.
     */
    @Test
    void approvedExpenses_reduceTheAdvanceAutomatically_andTheOffsetEndpointIsGone() throws Exception {
        Setup s = setup();
        String advanceId = approvedAndDisbursedAdvance(s, "10000.00");
        String reportId = approvedReportAgainst(s, advanceId, "6000.00");

        assertThat(amount(advance(advanceId), "$.data.outstandingBalance")).isEqualByComparingTo("4000.00");

        postJson("/xms/api/v1/expense-reports/" + reportId + "/apply-advance-offset", FINANCE,
                "{\"cashAdvanceId\":\"" + advanceId + "\"}", 404);
    }
}
