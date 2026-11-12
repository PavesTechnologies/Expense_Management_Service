package com.expense_management_service.e2e;

import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.ExpenseCategory;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenario: a regular expense with no cash advance - submission, manager approval, Finance verification and the
 * AP payment - checking the report's status, payment status and amounts after each stage.
 */
class RegularExpenseWorkflowE2eTest extends E2eTestBase {

    @Test
    void regularExpense_isApprovedVerifiedAndPaid_andOnlyBecomesPaidWhenApConfirms() throws Exception {
        CostCenter costCenter = costCenter(null, null);
        ExpenseCategory category = category();
        Persona employee = employee(costCenter);

        String reportId = createReport(employee, costCenter, null);
        String lineItemId = addLine(employee, reportId, category, "1500.00", null);
        String draft = report(reportId, employee);
        assertThat((String) read(draft, "$.data.reportStatus")).isEqualTo("DRAFT");
        assertThat(amount(draft, "$.data.totalAmount")).isEqualByComparingTo("1500.00");

        submitReport(employee, reportId);
        String submitted = report(reportId, employee);
        assertThat((String) read(submitted, "$.data.reportStatus")).isEqualTo("PENDING_APPROVAL");
        assertThat((String) read(submitted, "$.data.paymentRoutingStatus")).isEqualTo("NONE");

        approveAndVerify(reportId, lineItemId);
        String approved = report(reportId, employee);
        assertThat((String) read(approved, "$.data.paymentRoutingStatus")).isEqualTo("APPROVED_FOR_PAYMENT");
        assertThat(amount(approved, "$.data.reimbursableAmount")).isEqualByComparingTo("1500.00");
        // Approved and routed to AP is not paid.
        assertThat((Object) read(approved, "$.data.paymentConfirmation")).isNull();

        // AP must confirm the exact amount owed.
        postJson("/xms/ap-payments/" + reportId + "/complete", AP, """
                {"paymentReference":"UTR-WRONG","paymentMethod":"BANK_TRANSFER","paymentDate":"%s","amountPaid":1400.00}
                """.formatted(java.time.LocalDate.now()), 422);
        assertThat((String) read(report(reportId, employee), "$.data.paymentRoutingStatus")).isEqualTo("APPROVED_FOR_PAYMENT");

        apCompletesPayment(reportId, "1500.00", "UTR-1500");
        String paid = report(reportId, employee);
        assertThat((String) read(paid, "$.data.paymentRoutingStatus")).isEqualTo("PAYMENT_COMPLETED");
        assertThat((String) read(paid, "$.data.paymentConfirmation.paymentReference")).isEqualTo("UTR-1500");
        assertThat(amount(paid, "$.data.paymentConfirmation.amountPaid")).isEqualByComparingTo("1500.00");

        // A second confirmation is refused: the report can only be paid once.
        postJson("/xms/ap-payments/" + reportId + "/complete", AP, """
                {"paymentReference":"UTR-AGAIN","paymentMethod":"BANK_TRANSFER","paymentDate":"%s","amountPaid":1500.00}
                """.formatted(java.time.LocalDate.now()), 400);

        assertThat(auditLogRepository.findTop200ByEntityNameAndEntityIdOrderByPerformedAtDesc("ExpenseReport", UUID.fromString(reportId)))
                .extracting(AuditLog::getAction)
                .contains("PAYMENT_COMPLETED");
    }
}
