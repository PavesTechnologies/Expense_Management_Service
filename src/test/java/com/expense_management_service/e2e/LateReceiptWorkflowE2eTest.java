package com.expense_management_service.e2e;

import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.ExpenseCategory;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * A late receipt on a settled report, end to end: the employee attaches it, Finance rejects or verifies it through the
 * real controllers, transactions and after-commit listeners, and the approving manager is notified of a rejection.
 */
class LateReceiptWorkflowE2eTest extends E2eTestBase {

    /**
     * A Finance Executive who is not the shared FINANCE persona. Upper case because the e2e {@code as()} helper builds
     * authorities without the upper-casing JwtAuthConverter does; mixed-case roles are covered in LateReceiptAttachmentTest.
     */
    private static final Persona FINANCE_MIXED_CASE = new Persona("FIN009", UUID.fromString("00000000-0000-0000-0000-00000000a009"),
            9009L, List.of("FINANCE_EXECUTIVE"));

    private String approvedReportWithLateReceipt(Persona employee, CostCenter costCenter) throws Exception {
        ExpenseCategory category = category();
        String reportId = createReport(employee, costCenter, null);
        String lineItemId = addLine(employee, reportId, category, "400.00", null);
        submitReport(employee, reportId);
        approveAndVerify(reportId, lineItemId);

        MockMultipartFile file = new MockMultipartFile("file", "cab-late.pdf", "application/pdf",
                ("%PDF-1.4 late cab receipt " + UUID.randomUUID()).getBytes());
        String attached = call(multipart("/xms/employee/expense-line-items/" + lineItemId + "/late-receipts")
                .file(file)
                .param("actualReceiptDate", LocalDate.now().toString())
                .param("reason", "The cab company emailed the receipt a week later"), employee, 201);
        return read(attached, "$.data.receiptId");
    }

    @Test
    void financeRejectsALateReceipt_andTheManagerWhoApprovedIsNotified() throws Exception {
        CostCenter costCenter = costCenter(null, null);
        Persona employee = employee(costCenter);
        String receiptId = approvedReportWithLateReceipt(employee, costCenter);

        String queue = getJson("/xms/finance/late-receipts", FINANCE_MIXED_CASE);
        assertThat((List<String>) read(queue, "$.data[*].receiptId")).contains(receiptId);

        String rejected = postJson("/xms/finance/late-receipts/" + receiptId + "/reject", FINANCE_MIXED_CASE,
                "{\"note\":\"The receipt is for a different trip\"}", 200);
        assertThat((String) read(rejected, "$.data.verificationStatus")).isEqualTo("REJECTED");
        assertThat((String) read(rejected, "$.data.expenseSource")).isEqualTo("DIRECT");

        String inbox = getJson("/xms/notifications?size=50", MANAGER);
        assertThat(inbox).contains("LATE_RECEIPT_REJECTED");

        // It leaves the work queue but stays visible in the reviewed history.
        assertThat((List<String>) read(getJson("/xms/finance/late-receipts", FINANCE_MIXED_CASE), "$.data[*].receiptId"))
                .doesNotContain(receiptId);
        assertThat((List<String>) read(getJson("/xms/finance/late-receipts?status=REJECTED", FINANCE_MIXED_CASE), "$.data[*].receiptId"))
                .contains(receiptId);
        assertThat((List<String>) read(getJson("/xms/finance/late-receipts?status=ALL", FINANCE_MIXED_CASE), "$.data[*].receiptId"))
                .contains(receiptId);
    }

    @Test
    void financeVerifiesALateReceipt() throws Exception {
        CostCenter costCenter = costCenter(null, null);
        Persona employee = employee(costCenter);
        String receiptId = approvedReportWithLateReceipt(employee, costCenter);

        String verified = postJson("/xms/finance/late-receipts/" + receiptId + "/verify", FINANCE_MIXED_CASE,
                "{\"note\":null}", 200);
        assertThat((String) read(verified, "$.data.verificationStatus")).isEqualTo("VERIFIED");
    }
}
