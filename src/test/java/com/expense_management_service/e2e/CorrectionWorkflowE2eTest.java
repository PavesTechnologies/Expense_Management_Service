package com.expense_management_service.e2e;

import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.ExpenseCategory;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenario: a corrected expense - correction requested by the manager or by Finance, the employee's fix and
 * resubmission, re-approval, and payment of the corrected amount - with the report's history kept throughout.
 */
class CorrectionWorkflowE2eTest extends E2eTestBase {

    private String status(String reportId, Persona viewer) throws Exception {
        return getJson("/xms/approvals/" + reportId + "/status", viewer);
    }

    private String reportStatus(String reportId, Persona employee) throws Exception {
        return read(report(reportId, employee), "$.data.reportStatus");
    }

    @Test
    void managerRequestsACorrection_employeeFixesTheAmount_andTheCorrectedAmountIsReapprovedAndPaid() throws Exception {
        CostCenter costCenter = costCenter(null, null);
        ExpenseCategory category = category();
        Persona employee = employee(costCenter);

        String reportId = createReport(employee, costCenter, null);
        String lineItemId = addLine(employee, reportId, category, "1500.00", null);
        submitReport(employee, reportId);

        // A correction must say what to fix.
        postJson("/xms/approvals/" + reportId + "/line-items/" + lineItemId + "/review", MANAGER,
                "{\"decision\":\"NEEDS_CORRECTION\",\"comment\":null}", 400);
        managerReviews(reportId, lineItemId, "NEEDS_CORRECTION", "Taxi fare on the receipt is 1200");
        assertThat(reportStatus(reportId, employee)).isEqualTo("AWAITING_CORRECTION");
        String requested = status(reportId, employee);
        assertThat((String) read(requested, "$.data.correctionRequestedBy")).isEqualTo("APPROVER");
        assertThat((String) read(requested, "$.data.correctionRequests[0].comment")).isEqualTo("Taxi fare on the receipt is 1200");

        putJson("/xms/employee/expense-reports/" + reportId + "/line-items/" + lineItemId, employee,
                lineJson(category, "1200.00", "Taxi and meals (corrected)", null), 200);
        assertThat(amount(report(reportId, employee), "$.data.totalAmount")).isEqualByComparingTo("1200.00");

        submitReport(employee, reportId);
        assertThat(reportStatus(reportId, employee)).isEqualTo("PENDING_APPROVAL");

        approveAndVerify(reportId, lineItemId);
        String approved = report(reportId, employee);
        assertThat(amount(approved, "$.data.reimbursableAmount")).isEqualByComparingTo("1200.00");

        // AP pays the corrected amount, not the original.
        postJson("/xms/ap-payments/" + reportId + "/complete", AP, """
                {"paymentReference":"UTR-OLD","paymentMethod":"BANK_TRANSFER","paymentDate":"%s","amountPaid":1500.00}
                """.formatted(java.time.LocalDate.now()), 422);
        apCompletesPayment(reportId, "1200.00", "UTR-1200");
        assertThat((String) read(report(reportId, employee), "$.data.paymentRoutingStatus")).isEqualTo("PAYMENT_COMPLETED");

        // The whole history is kept: submission, the correction, the resubmission, approval and payment.
        assertThat(auditLogRepository.findTop200ByEntityNameAndEntityIdOrderByPerformedAtDesc("ExpenseReport", UUID.fromString(reportId)))
                .extracting(AuditLog::getAction)
                .contains("REPORT_SUBMITTED", "REPORT_AWAITING_CORRECTION", "REPORT_RESUMED", "REPORT_APPROVED", "PAYMENT_COMPLETED");
    }

    @Test
    void financeQueriesALine_aMinorFixResumesAtFinance_withoutGoingBackToTheManager() throws Exception {
        CostCenter costCenter = costCenter(null, null);
        ExpenseCategory category = category();
        Persona employee = employee(costCenter);

        String reportId = createReport(employee, costCenter, null);
        String lineItemId = addLine(employee, reportId, category, "900.00", null);
        submitReport(employee, reportId);
        managerReviews(reportId, lineItemId, "APPROVED", null);
        assertThat(reportStatus(reportId, employee)).isEqualTo("PENDING_FINANCE_VERIFICATION");

        postJson("/xms/finance-verification/" + reportId + "/line-items/" + lineItemId + "/query", FINANCE,
                "{\"reason\":\"Say which client meeting this was for\",\"category\":\"OTHER\"}", 200);
        assertThat(reportStatus(reportId, employee)).isEqualTo("AWAITING_CORRECTION");
        assertThat((String) read(status(reportId, employee), "$.data.correctionRequestedBy")).isEqualTo("FINANCE");

        // Only the description changes - not material, so the manager's approval stands.
        putJson("/xms/employee/expense-reports/" + reportId + "/line-items/" + lineItemId, employee,
                lineJson(category, "900.00", "Taxi to the Acme kickoff meeting", null), 200);
        submitReport(employee, reportId);
        assertThat(reportStatus(reportId, employee)).isEqualTo("PENDING_FINANCE_VERIFICATION");

        financeVerifies(reportId, lineItemId);
        String approved = report(reportId, employee);
        assertThat((String) read(approved, "$.data.reportStatus")).isEqualTo("APPROVED");
        assertThat((String) read(approved, "$.data.paymentRoutingStatus")).isEqualTo("APPROVED_FOR_PAYMENT");
        assertThat(amount(approved, "$.data.reimbursableAmount")).isEqualByComparingTo("900.00");
    }

    @Test
    void aMaterialFixAfterAFinanceQuery_sendsTheReportBackToTheManager() throws Exception {
        CostCenter costCenter = costCenter(null, null);
        ExpenseCategory category = category();
        Persona employee = employee(costCenter);

        String reportId = createReport(employee, costCenter, null);
        String lineItemId = addLine(employee, reportId, category, "1000.00", null);
        submitReport(employee, reportId);
        managerReviews(reportId, lineItemId, "APPROVED", null);

        postJson("/xms/finance-verification/" + reportId + "/line-items/" + lineItemId + "/query", FINANCE,
                "{\"reason\":\"Amount doesn't match the receipt\",\"category\":\"AMOUNT\"}", 200);
        // A 50% change in the amount is material: the manager has to approve it again.
        putJson("/xms/employee/expense-reports/" + reportId + "/line-items/" + lineItemId, employee,
                lineJson(category, "1500.00", "Taxi and meals", null), 200);
        submitReport(employee, reportId);
        assertThat(reportStatus(reportId, employee)).isEqualTo("PENDING_APPROVAL");
        assertThat(((Number) read(status(reportId, employee), "$.data.currentLevelOrder")).intValue()).isEqualTo(1);
    }
}
