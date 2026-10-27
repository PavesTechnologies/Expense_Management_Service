package com.expense_management_service.e2e;

import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ProjectCache;
import com.expense_management_service.integration.pms.dto.PmsProjectDetailResponse;
import com.expense_management_service.integration.pms.dto.PmsProjectSummaryResponse;
import com.expense_management_service.repository.ProjectCacheRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenario: a client-billable expense - approved, verified by Finance, listed for invoicing, handed off with the
 * PMS project for AR to read, and reimbursed by AP independently of the handoff.
 * <p>
 * The rest of the requested path (customer payment received, then reconciled) isn't built yet - the statuses
 * and the AR-to-XMS update are pending items 13 and 14 - so the scenario stops at the handoff.
 */
class ClientBillableWorkflowE2eTest extends E2eTestBase {

    private static final Persona AR = new Persona("AR001", UUID.fromString("00000000-0000-0000-0000-00000000a004"), 9004L, List.of("AR_SERVICE"));

    @Autowired private ProjectCacheRepository projectCacheRepository;

    private final UUID clientId = UUID.randomUUID();
    private final long pmsProjectId = ThreadLocalRandom.current().nextLong(100_000, 1_000_000_000);

    private String handedOff() throws Exception {
        return getJson("/xms/finance/invoice-handoff-queue/handed-off?pmsProjectId=" + pmsProjectId, FINANCE);
    }

    private ProjectCache project() {
        String code = "PRJ-" + suffix();
        ProjectCache project = projectCacheRepository.save(ProjectCache.builder().projectCode(code).projectName("Acme rollout")
                .pmsProjectId(pmsProjectId).clientId(clientId).clientName("Acme Corp").status("ACTIVE").build());
        pmsProjectIs(code, clientId);
        return project;
    }

    /** What PMS reports for the project: the employee is assigned to it, and it belongs to {@code client}. */
    private void pmsProjectIs(String code, UUID client) {
        org.mockito.Mockito.when(pmsClient.getMyActiveProjects(42L))
                .thenReturn(List.of(new PmsProjectSummaryResponse(pmsProjectId, code, "Acme rollout", null, "ACTIVE")));
        org.mockito.Mockito.when(pmsClient.getProject(pmsProjectId))
                .thenReturn(Optional.of(new PmsProjectDetailResponse(pmsProjectId, code, "Acme rollout", null, "ACTIVE",
                        client, null, null, null, null)));
    }

    @Test
    void clientBillableExpense_isHandedOffToArOnce_withThePmsProject_whileApPaysTheEmployeeSeparately() throws Exception {
        CostCenter costCenter = costCenter(null, null);
        ExpenseCategory category = category();
        Persona employee = employee(costCenter);
        ProjectCache project = project();

        String reportId = createReport(employee, costCenter, null);
        String lineItemId = addLine(employee, reportId, category, "2500.00",
                "\"clientBillable\":true,\"projectId\":\"" + project.getProjectId() + "\"");
        submitReport(employee, reportId);

        // Not invoiceable before Finance has verified it.
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/xms/finance/invoice-handoff-queue/" + lineItemId + "/handoff")
                .contentType("application/json").content("{\"invoiceReference\":\"INV-EARLY\"}"), FINANCE, 422);

        approveAndVerify(reportId, lineItemId);
        String approved = report(reportId, employee);
        assertThat((String) read(approved, "$.data.invoiceHandoffStatus")).isEqualTo("PENDING");
        assertThat((String) read(approved, "$.data.paymentRoutingStatus")).isEqualTo("APPROVED_FOR_PAYMENT");

        String eligible = getJson("/xms/finance/invoice-handoff-queue/eligible-expenses?pmsProjectId=" + pmsProjectId, FINANCE);
        assertThat((List<String>) read(eligible, "$.data.content[*].lineItemId")).containsExactly(lineItemId);
        assertThat((String) read(eligible, "$.data.content[0].resolvedClientName")).isEqualTo("Acme Corp");

        // AR reads what it will bill: the cost basis, keyed by the PMS project id.
        String payload = getJson("/xms/finance/invoice-handoff-queue/" + lineItemId + "/billing-payload", AR);
        assertThat((Integer) read(payload, "$.data.payloadVersion")).isEqualTo(2);
        assertThat(((Number) read(payload, "$.data.pmsProjectId")).longValue()).isEqualTo(pmsProjectId);
        assertThat((String) read(payload, "$.data.projectCode")).isEqualTo(project.getProjectCode());
        assertThat((String) read(payload, "$.data.clientId")).isEqualTo(clientId.toString());
        assertThat(amount(payload, "$.data.costBasis")).isEqualByComparingTo("2500.00");
        assertThat((String) read(payload, "$.data.handoffStatus")).isEqualTo("PENDING");

        // Only Finance hands off; AR can read but not mark it.
        postJson("/xms/finance/invoice-handoff-queue/" + lineItemId + "/handoff", AR, "{\"invoiceReference\":\"INV-AR\"}", 403);
        String handoff = postJson("/xms/finance/invoice-handoff-queue/" + lineItemId + "/handoff", FINANCE,
                "{\"invoiceReference\":\"INV-2026-001\"}", 200);
        assertThat((String) read(handoff, "$.data.syncStatus")).isEqualTo("HANDED_OFF");
        String handoffId = read(handoff, "$.data.syncId");

        // The handoff is recorded, and AR can read it back with what it needs to bill.
        String recorded = getJson("/xms/finance/invoice-handoff-queue/handed-off?pmsProjectId=" + pmsProjectId, AR);
        assertThat((String) read(recorded, "$.data.content[0].syncId")).isEqualTo(handoffId);
        assertThat((String) read(recorded, "$.data.content[0].lineItemId")).isEqualTo(lineItemId);
        assertThat((String) read(recorded, "$.data.content[0].resolvedClientId")).isEqualTo(clientId.toString());
        assertThat(new java.math.BigDecimal(read(recorded, "$.data.content[0].costBasis").toString())).isEqualByComparingTo("2500.00");
        assertThat((String) read(recorded, "$.data.content[0].invoiceReference")).isEqualTo("INV-2026-001");

        assertThat((String) read(report(reportId, employee), "$.data.invoiceHandoffStatus")).isEqualTo("COMPLETED");
        String handedOffPayload = getJson("/xms/finance/invoice-handoff-queue/" + lineItemId + "/billing-payload", AR);
        assertThat((String) read(handedOffPayload, "$.data.handoffStatus")).isEqualTo("HANDED_OFF");
        assertThat((String) read(handedOffPayload, "$.data.invoiceReference")).isEqualTo("INV-2026-001");
        String afterHandoff = getJson("/xms/finance/invoice-handoff-queue/eligible-expenses?pmsProjectId=" + pmsProjectId, FINANCE);
        assertThat((List<String>) read(afterHandoff, "$.data.content[*].lineItemId")).isEmpty();

        // Never handed off twice by accident; a deliberate reprocess needs a reason.
        postJson("/xms/finance/invoice-handoff-queue/" + lineItemId + "/handoff", FINANCE, "{\"invoiceReference\":\"INV-DUP\"}", 409);
        postJson("/xms/finance/invoice-handoff-queue/" + lineItemId + "/handoff", FINANCE,
                "{\"invoiceReference\":\"INV-2026-001R\",\"allowReprocess\":true,\"reason\":\"Invoice voided and reissued\"}", 200);
        // The re-handoff is a new record that carries the reason.
        assertThat((List<String>) read(handedOff(), "$.data.content[*].remarks"))
                .contains("Reprocessed: Invoice voided and reissued");

        // Reimbursing the employee is a separate track: AP pays, and the invoicing state is untouched.
        apCompletesPayment(reportId, "2500.00", "UTR-BILLABLE");
        String paid = report(reportId, employee);
        assertThat((String) read(paid, "$.data.paymentRoutingStatus")).isEqualTo("PAYMENT_COMPLETED");
        assertThat((String) read(paid, "$.data.invoiceHandoffStatus")).isEqualTo("COMPLETED");
    }

    @Test
    void handoff_isRefused_whenPmsNoLongerHasTheProjectUnderTheBilledClient() throws Exception {
        CostCenter costCenter = costCenter(null, null);
        ExpenseCategory category = category();
        Persona employee = employee(costCenter);
        ProjectCache project = project();

        String reportId = createReport(employee, costCenter, null);
        String lineItemId = addLine(employee, reportId, category, "800.00",
                "\"clientBillable\":true,\"projectId\":\"" + project.getProjectId() + "\"");
        submitReport(employee, reportId);
        approveAndVerify(reportId, lineItemId);

        // Between approval and handoff, PMS moved the project to another client.
        pmsProjectIs(project.getProjectCode(), UUID.randomUUID());

        String refused = postJson("/xms/finance/invoice-handoff-queue/" + lineItemId + "/handoff", FINANCE,
                "{\"invoiceReference\":\"INV-MOVED\"}", 422);
        assertThat((String) read(refused, "$.message")).contains("no longer belongs to");
        assertThat((String) read(report(reportId, employee), "$.data.invoiceHandoffStatus")).isEqualTo("PENDING");
    }
}
