package com.expense_management_service.e2e;

import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.CostCenterBudget;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.service.impl.CashAdvanceCarryForwardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The two ways an employee can ask for a cash advance, end to end over HTTP:
 * <ul>
 *   <li><b>Complete New</b>: the full amount; a reason is required while another advance still has a balance, and
 *       disbursement pays the full amount.</li>
 *   <li><b>Cash Advance + Carry Forward</b>: Finance carries an earlier advance's balance forward; the employee asks only
 *       for the new cash; on final approval the carried balance moves in, and disbursement pays only the new cash.</li>
 * </ul>
 * Each scenario starts from a 10000 advance with an approved 6000 report against it, leaving 4000 outstanding.
 */
class CashAdvanceRequestTypesE2eTest extends E2eTestBase {

    private static final BigDecimal BUDGET = new BigDecimal("100000.00");
    private static final String REASON = "Second client trip starts before the first one is settled";

    @Autowired
    private CashAdvanceCarryForwardService carryForwardService;

    private record Setup(CostCenter costCenter, Persona costCenterOwner, Persona employee, ExpenseCategory category) {
    }

    private Setup setup() {
        Persona owner = new Persona("CCO" + suffix(), UUID.randomUUID(), 77L, List.of("GENERAL"));
        CostCenter costCenter = costCenter(owner.employeeId(), BUDGET);
        return new Setup(costCenter, owner, employee(costCenter), category());
    }

    /** The carry-forward feature is off by default; turned on only inside the scenario that needs it. */
    private void carryForwardEnabled(boolean on) {
        CashAdvanceCarryForwardService target = AopTestUtils.getTargetObject(carryForwardService);
        ReflectionTestUtils.setField(target, "enabled", on);
    }

    @AfterEach
    void switchCarryForwardOff() {
        carryForwardEnabled(false);
    }

    private String advance(String advanceId) throws Exception {
        return getJson("/xms/employee/cash-advances/" + advanceId, FINANCE);
    }

    private BigDecimal available(CostCenter costCenter) {
        CostCenterBudget budget = costCenterBudgetRepository
                .findByCostCenter_CostCenterIdAndFiscalYear(costCenter.getCostCenterId(), currentFiscalYear()).orElseThrow();
        return budget.getAvailableBudget();
    }

    private String create(Setup s, String extraJson, int expectedStatus) throws Exception {
        return postJson("/xms/employee/cash-advances", s.employee(), """
                {"amount":5000.00,"currencyId":"%s","costCenterId":"%s","purpose":"Client visit travel advance",
                 "title":"Pune trip","neededByDate":"%s"%s}
                """.formatted(inr.getCurrencyId(), s.costCenter().getCostCenterId(), LocalDate.now().plusDays(20),
                extraJson), expectedStatus);
    }

    /** Submit, then manager -> cost center owner -> Finance. */
    private void submitAndApprove(Setup s, String advanceId) throws Exception {
        call(post("/xms/employee/cash-advances/" + advanceId + "/submit"), s.employee(), 200);
        call(post("/xms/employee/cash-advances/" + advanceId + "/approve"), MANAGER, 200);
        call(post("/xms/employee/cash-advances/" + advanceId + "/approve"), s.costCenterOwner(), 200);
        call(post("/xms/employee/cash-advances/" + advanceId + "/approve"), FINANCE, 200);
        assertThat((String) read(advance(advanceId), "$.data.status")).isEqualTo("APPROVED");
    }

    private void disburse(String advanceId) throws Exception {
        call(multipart("/xms/employee/cash-advances/" + advanceId + "/disburse")
                .param("disbursementMode", "BANK_TRANSFER").param("transactionReference", "UTR-ADV-" + suffix())
                .param("transferDate", LocalDate.now().toString()).param("transferDetails", "HDFC ****1234"), FINANCE, 200);
        assertThat((String) read(advance(advanceId), "$.data.status")).isEqualTo("IN_PROGRESS");
    }

    /** A disbursed 10000 Complete New advance with an approved 6000 report against it: 4000 still outstanding. */
    private String firstAdvanceWith4000Outstanding(Setup s) throws Exception {
        String advanceId = read(postJson("/xms/employee/cash-advances", s.employee(), """
                {"amount":10000.00,"currencyId":"%s","costCenterId":"%s","purpose":"Client visit travel advance",
                 "title":"Mumbai trip","neededByDate":"%s"}
                """.formatted(inr.getCurrencyId(), s.costCenter().getCostCenterId(), LocalDate.now().plusDays(20)), 201),
                "$.data.advanceId");
        submitAndApprove(s, advanceId);
        disburse(advanceId);

        String reportId = createReport(s.employee(), s.costCenter(), UUID.fromString(advanceId));
        String lineItemId = addLine(s.employee(), reportId, s.category(), "6000.00", null);
        submitReport(s.employee(), reportId);
        approveAndVerify(reportId, lineItemId);
        assertThat(amount(advance(advanceId), "$.data.outstandingBalance")).isEqualByComparingTo("4000.00");
        return advanceId;
    }

    // ------------------------------------------------------------------ Complete New

    @Test
    void completeNew_needsAReasonWhileMoneyIsOwed_andPaysTheFullAmount() throws Exception {
        Setup s = setup();
        String first = firstAdvanceWith4000Outstanding(s);

        // 4000 is still owed on the first advance, so a separate advance needs a reason.
        create(s, ",\"requestType\":\"COMPLETE_NEW\"", 422);
        String created = create(s, ",\"requestType\":\"COMPLETE_NEW\",\"employeeJustification\":\"" + REASON + "\"", 201);
        String advanceId = read(created, "$.data.advanceId");
        assertThat((String) read(created, "$.data.requestType")).isEqualTo("COMPLETE_NEW");
        assertThat(amount(created, "$.data.totalAdvanceAllocation")).isEqualByComparingTo("5000.00");

        submitAndApprove(s, advanceId);
        disburse(advanceId);

        String body = advance(advanceId);
        assertThat(amount(body, "$.data.amount")).isEqualByComparingTo("5000.00");
        assertThat(amount(body, "$.data.disbursedAmount")).isEqualByComparingTo("5000.00");
        assertThat(amount(body, "$.data.outstandingBalance")).isEqualByComparingTo("5000.00");
        // The first advance's balance stays where it was.
        assertThat(amount(advance(first), "$.data.outstandingBalance")).isEqualByComparingTo("4000.00");
    }

    @Test
    void completeNew_needsNoReasonWhenNothingIsOwed() throws Exception {
        Setup s = setup();
        String created = create(s, ",\"requestType\":\"COMPLETE_NEW\"", 201);
        String advanceId = read(created, "$.data.advanceId");

        submitAndApprove(s, advanceId);
        disburse(advanceId);

        assertThat(amount(advance(advanceId), "$.data.disbursedAmount")).isEqualByComparingTo("5000.00");
    }

    // ------------------------------------------------------------------ Cash Advance + Carry Forward

    @Test
    void carryForward_isRefusedWhileTheFeatureIsOff() throws Exception {
        Setup s = setup();
        create(s, ",\"requestType\":\"ADVANCE_PLUS_CARRY_FORWARD\",\"additionalAmount\":5000.00", 422);
    }

    @Test
    void carryForward_isRefusedWhenNothingWasCarriedForward() throws Exception {
        carryForwardEnabled(true);
        Setup s = setup();
        create(s, ",\"requestType\":\"ADVANCE_PLUS_CARRY_FORWARD\",\"additionalAmount\":5000.00", 422);
    }

    @Test
    void carryForward_inAnotherCurrency_isNotCounted_andSaysWhichCurrencyToUse() throws Exception {
        carryForwardEnabled(true);
        Setup s = setup();
        String first = firstAdvanceWith4000Outstanding(s);
        postJson("/xms/cash-advance-carry-forwards/ready", FINANCE,
                "{\"advanceId\":\"" + first + "\",\"amount\":550.00,\"reason\":\"Trip extended, balance to the next trip\"}", 200);
        com.expense_management_service.entity.Currency sgd = currencyRepository.findByCurrencyCodeIgnoreCase("SGD")
                .orElseGet(() -> currencyRepository.save(com.expense_management_service.entity.Currency.builder()
                        .currencyCode("SGD").currencyName("Singapore Dollar").symbol("S$").decimalPlaces(2).status("ACTIVE").build()));

        // The held 550 is INR. An SGD request must not add it (2000 SGD + 550 INR is not 2550 of anything).
        String refused = postJson("/xms/employee/cash-advances/preview", s.employee(),
                "{\"requestType\":\"ADVANCE_PLUS_CARRY_FORWARD\",\"amount\":2000.00,\"currencyId\":\"" + sgd.getCurrencyId() + "\"}", 422);
        assertThat(refused).contains("INR").contains("SGD");

        // In INR it is counted.
        String preview = postJson("/xms/employee/cash-advances/preview", s.employee(),
                "{\"requestType\":\"ADVANCE_PLUS_CARRY_FORWARD\",\"amount\":2000.00,\"currencyId\":\"" + inr.getCurrencyId() + "\"}", 200);
        assertThat(amount(preview, "$.data.totalAdvanceAllocation")).isEqualByComparingTo("2550.00");
    }

    @Test
    void carryForward_movesOnlyWhatTheRequestShowed_notABalanceCarriedForwardAfterSubmission() throws Exception {
        carryForwardEnabled(true);
        Setup s = setup();
        String first = firstAdvanceWith4000Outstanding(s);
        String readyJson = "{\"advanceId\":\"" + first + "\",\"amount\":1000.00,\"reason\":\"Trip extended, balance to the next trip\"}";
        postJson("/xms/cash-advance-carry-forwards/ready", FINANCE, readyJson, 200);

        String created = create(s, ",\"requestType\":\"ADVANCE_PLUS_CARRY_FORWARD\",\"additionalAmount\":5000.00", 201);
        String advanceId = read(created, "$.data.advanceId");
        assertThat(amount(created, "$.data.totalAdvanceAllocation")).isEqualByComparingTo("6000.00");
        call(post("/xms/employee/cash-advances/" + advanceId + "/submit"), s.employee(), 200);

        // Finance carries another 1000 forward after the request was submitted.
        postJson("/xms/cash-advance-carry-forwards/ready", FINANCE, readyJson, 200);
        assertThat(amount(advance(first), "$.data.carryForwardReadyAmount")).isEqualByComparingTo("2000.00");

        call(post("/xms/employee/cash-advances/" + advanceId + "/approve"), MANAGER, 200);
        call(post("/xms/employee/cash-advances/" + advanceId + "/approve"), s.costCenterOwner(), 200);
        call(post("/xms/employee/cash-advances/" + advanceId + "/approve"), FINANCE, 200);

        String approved = advance(advanceId);
        assertThat(amount(approved, "$.data.amount")).isEqualByComparingTo("6000.00");
        assertThat(amount(approved, "$.data.carriedInAmount")).isEqualByComparingTo("1000.00");
        assertThat(amount(approved, "$.data.totalAdvanceAllocation")).isEqualByComparingTo("6000.00");
        // The later 1000 stays held for the employee's next request.
        assertThat(amount(advance(first), "$.data.carryForwardReadyAmount")).isEqualByComparingTo("1000.00");
    }

    @Test
    void carryForward_movesTheHeldBalanceIn_onApproval_andPaysOnlyTheNewCash() throws Exception {
        carryForwardEnabled(true);
        Setup s = setup();
        String first = firstAdvanceWith4000Outstanding(s);

        // 1. Finance carries the 4000 forward. It leaves the first advance and is held for the next request.
        postJson("/xms/cash-advance-carry-forwards/ready", FINANCE,
                "{\"advanceId\":\"" + first + "\",\"amount\":4000.00,\"reason\":\"Trip extended, balance to the next trip\"}", 200);
        String firstBody = advance(first);
        assertThat(amount(firstBody, "$.data.carryForwardReadyAmount")).isEqualByComparingTo("4000.00");
        assertThat(amount(firstBody, "$.data.outstandingBalance")).isEqualByComparingTo("0.00");

        // 2. Preview: 4000 carried + 5000 new cash = 9000; no reason needed.
        String preview = postJson("/xms/employee/cash-advances/preview", s.employee(),
                "{\"requestType\":\"ADVANCE_PLUS_CARRY_FORWARD\",\"amount\":5000.00,\"currencyId\":\"" + inr.getCurrencyId() + "\"}", 200);
        assertThat(amount(preview, "$.data.eligibleOutstanding")).isEqualByComparingTo("4000.00");
        assertThat(amount(preview, "$.data.totalAdvanceAllocation")).isEqualByComparingTo("9000.00");
        assertThat((Boolean) read(preview, "$.data.justificationRequired")).isFalse();

        // 3. The employee asks for only the new cash.
        String created = create(s, ",\"requestType\":\"ADVANCE_PLUS_CARRY_FORWARD\",\"additionalAmount\":5000.00", 201);
        String advanceId = read(created, "$.data.advanceId");
        assertThat((String) read(created, "$.data.requestType")).isEqualTo("ADVANCE_PLUS_CARRY_FORWARD");
        assertThat(amount(created, "$.data.totalAdvanceAllocation")).isEqualByComparingTo("9000.00");

        // 4. Approval chain. Nothing moves before Finance's final approval.
        BigDecimal budgetBefore = available(s.costCenter());
        submitAndApprove(s, advanceId);
        String approved = advance(advanceId);
        assertThat(amount(approved, "$.data.amount")).isEqualByComparingTo("9000.00");
        assertThat(amount(approved, "$.data.carriedInAmount")).isEqualByComparingTo("4000.00");
        assertThat(amount(approved, "$.data.approvedAmount")).isEqualByComparingTo("9000.00");
        assertThat((Object) read(advance(first), "$.data.carryForwardReadyAmount")).isNull();
        // The budget is charged for the new cash only: the carried 4000 was budgeted on the first advance.
        BigDecimal charged = budgetBefore.subtract(available(s.costCenter()));
        assertThat(charged).isGreaterThanOrEqualTo(new BigDecimal("5000.00")).isLessThan(new BigDecimal("9000.00"));

        // 5. Disbursement pays the 5000 new cash; the employee accounts for the full 9000.
        disburse(advanceId);
        String paid = advance(advanceId);
        assertThat(amount(paid, "$.data.disbursedAmount")).isEqualByComparingTo("5000.00");
        assertThat(amount(paid, "$.data.outstandingBalance")).isEqualByComparingTo("9000.00");
    }

    /**
     * A carry-forward shown on a pending request is not offered to a second request. Once the first is rejected it never
     * moved anything, so the balance is offered again.
     */
    @Test
    void carryForward_claimedByAPendingRequest_isNotOfferedAgain_untilThatRequestIsRejected() throws Exception {
        carryForwardEnabled(true);
        Setup s = setup();
        String first = firstAdvanceWith4000Outstanding(s);
        postJson("/xms/cash-advance-carry-forwards/ready", FINANCE,
                "{\"advanceId\":\"" + first + "\",\"amount\":4000.00,\"reason\":\"Trip extended, balance to the next trip\"}", 200);
        String previewJson = "{\"requestType\":\"ADVANCE_PLUS_CARRY_FORWARD\",\"amount\":5000.00,\"currencyId\":\"" + inr.getCurrencyId() + "\"}";

        // Request A takes the 4000 in and is submitted.
        String a = read(create(s, ",\"requestType\":\"ADVANCE_PLUS_CARRY_FORWARD\",\"additionalAmount\":5000.00", 201), "$.data.advanceId");
        call(post("/xms/employee/cash-advances/" + a + "/submit"), s.employee(), 200);

        // Request B cannot show the same 4000 while A is pending.
        String refused = postJson("/xms/employee/cash-advances/preview", s.employee(), previewJson, 422);
        assertThat(refused).contains("already included").contains("rejected or cancelled");
        create(s, ",\"requestType\":\"ADVANCE_PLUS_CARRY_FORWARD\",\"additionalAmount\":5000.00", 422);
        // A's own draft-style preview still sees its claim.
        String own = postJson("/xms/employee/cash-advances/preview", s.employee(),
                previewJson.replace("}", ",\"advanceId\":\"" + a + "\"}"), 200);
        assertThat(amount(own, "$.data.totalAdvanceAllocation")).isEqualByComparingTo("9000.00");

        // A is rejected: nothing moved, so the 4000 is offered to the next request.
        call(post("/xms/employee/cash-advances/" + a + "/reject").param("reason", "Trip postponed to next month"), MANAGER, 200);
        assertThat(amount(advance(first), "$.data.carryForwardReadyAmount")).isEqualByComparingTo("4000.00");
        String preview = postJson("/xms/employee/cash-advances/preview", s.employee(), previewJson, 200);
        assertThat(amount(preview, "$.data.totalAdvanceAllocation")).isEqualByComparingTo("9000.00");
        String b = create(s, ",\"requestType\":\"ADVANCE_PLUS_CARRY_FORWARD\",\"additionalAmount\":5000.00", 201);
        assertThat(amount(b, "$.data.totalAdvanceAllocation")).isEqualByComparingTo("9000.00");
    }

    /** The approvals screen path: the manager decides through /manager-decision, then Finance gives final approval. */
    @Test
    void carryForward_throughTheManagerDecision_reducedCash_keepsTheCarryForward_andRefusesASetOff() throws Exception {
        carryForwardEnabled(true);
        Setup s = setup();
        String first = firstAdvanceWith4000Outstanding(s);
        postJson("/xms/cash-advance-carry-forwards/ready", FINANCE,
                "{\"advanceId\":\"" + first + "\",\"amount\":4000.00,\"reason\":\"Trip extended, balance to the next trip\"}", 200);
        String advanceId = read(create(s, ",\"requestType\":\"ADVANCE_PLUS_CARRY_FORWARD\",\"additionalAmount\":5000.00", 201),
                "$.data.advanceId");
        call(post("/xms/employee/cash-advances/" + advanceId + "/submit"), s.employee(), 200);
        String decision = "/xms/employee/cash-advances/" + advanceId + "/manager-decision";

        // A set-off would bring the carried balance in twice.
        String refused = postJson(decision, MANAGER, "{\"decision\":\"SET_OFF\",\"setOffSourceAdvanceId\":\"" + first
                + "\",\"setOffAmount\":1000.00,\"justification\":\"Set off the earlier advance balance\"}", 422);
        assertThat(refused).contains("carried-forward");

        // The manager cuts the new cash from 5000 to 4000.
        postJson(decision, MANAGER, "{\"decision\":\"APPROVE_REDUCED\",\"approvedAmount\":4000.00,"
                + "\"justification\":\"Only two days of travel are needed\"}", 200);
        call(post("/xms/employee/cash-advances/" + advanceId + "/approve"), s.costCenterOwner(), 200);
        call(post("/xms/employee/cash-advances/" + advanceId + "/approve"), FINANCE, 200);

        String approved = advance(advanceId);
        assertThat(amount(approved, "$.data.amount")).isEqualByComparingTo("8000.00");
        assertThat(amount(approved, "$.data.carriedInAmount")).isEqualByComparingTo("4000.00");
        disburse(advanceId);
        String paid = advance(advanceId);
        assertThat(amount(paid, "$.data.disbursedAmount")).isEqualByComparingTo("4000.00");
        assertThat(amount(paid, "$.data.outstandingBalance")).isEqualByComparingTo("8000.00");
    }
}
