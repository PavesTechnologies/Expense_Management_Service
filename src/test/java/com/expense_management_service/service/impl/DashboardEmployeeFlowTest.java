package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.DashboardResponse;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.security.CurrentUserService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Employee money flows: the cash advance chain (requested, approved, disbursed, settled, outstanding) and the expense
 * chain (claimed, approved, covered by advance, waiting for AP, paid), plus the category donut, which must add up to the
 * claimed total.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardEmployeeFlowTest {

    private static final String ME = "EMP001";

    @Mock private CurrentUserService currentUserService;
    @Mock private CashAdvanceRepository cashAdvanceRepository;
    @Mock private CashAdvanceBalanceCalculator balanceCalculator;
    @InjectMocks private DashboardServiceImpl service;

    private EntityManager em;
    private UUID disbursedAdvanceId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        em = mock(EntityManager.class);
        ReflectionTestUtils.setField(service, "em", em);
        ReflectionTestUtils.setField(service, "baseCurrencyCode", "INR");
        when(currentUserService.getEmployeeId()).thenReturn(ME);
        disbursedAdvanceId = UUID.randomUUID();
    }

    private static ExpenseReport report(String number, ReportStatus status, PaymentRoutingStatus routing,
                                        String reimbursable, String paid) {
        return ExpenseReport.builder()
                .reportId(UUID.randomUUID())
                .reportNumber(number)
                .employeeId(ME)
                .title("Trip " + number)
                .reportStatus(status)
                .paymentRoutingStatus(routing)
                .approvedAt(LocalDateTime.now().minusDays(3))
                .updatedAt(LocalDateTime.now())
                .reimbursableAmount(reimbursable == null ? null : new BigDecimal(reimbursable))
                .paidAmount(paid == null ? null : new BigDecimal(paid))
                .build();
    }

    /** Links a report to a cash advance, as submitting it against an advance does. */
    private static ExpenseReport againstAdvance(ExpenseReport r) {
        r.setCashAdvance(CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId(ME).build());
        return r;
    }

    private static BigDecimal kpi(DashboardResponse r, String key) {
        return r.kpis().stream().filter(k -> key.equals(k.key())).findFirst()
                .orElseThrow(() -> new AssertionError("missing KPI " + key)).value();
    }

    @SuppressWarnings("unchecked")
    private void stubData(List<ExpenseReport> reports, List<Object[]> lines, List<Object[]> gross) {
        TypedQuery<ExpenseReport> reportQuery = mock(TypedQuery.class, RETURNS_SELF);
        when(reportQuery.getResultList()).thenReturn(reports);
        when(em.createQuery(contains("where r.employeeId = :me"), eq(ExpenseReport.class))).thenReturn(reportQuery);

        TypedQuery<Object[]> lineQuery = mock(TypedQuery.class, RETURNS_SELF);
        when(lineQuery.getResultList()).thenReturn(lines);
        when(em.createQuery(contains("left join l.category"), eq(Object[].class))).thenReturn(lineQuery);

        TypedQuery<Object[]> grossQuery = mock(TypedQuery.class, RETURNS_SELF);
        when(grossQuery.getResultList()).thenReturn(gross);
        when(em.createQuery(contains("sum(l.baseAmount)"), eq(Object[].class))).thenReturn(grossQuery);
    }

    @Test
    void cashAdvanceChain_requested_approved_disbursed_settled_outstanding() {
        CashAdvance disbursed = CashAdvance.builder().advanceId(disbursedAdvanceId).employeeId(ME)
                .amount(new BigDecimal("9000.00")).requestedAmount(new BigDecimal("10000.00"))
                .approvedAmount(new BigDecimal("9000.00")).disbursedAmount(new BigDecimal("9000.00"))
                .disbursedAt(LocalDateTime.now()).status("DISBURSED").build();
        CashAdvance approvedOnly = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId(ME)
                .amount(new BigDecimal("2000.00")).requestedAmount(new BigDecimal("2000.00"))
                .approvedAmount(new BigDecimal("2000.00")).status("APPROVED").build();
        CashAdvance draft = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId(ME)
                .amount(new BigDecimal("5000.00")).status("DRAFT").build();
        when(cashAdvanceRepository.findByEmployeeId(ME)).thenReturn(List.of(disbursed, approvedOnly, draft));
        when(balanceCalculator.partsFor(any())).thenReturn(Map.of(disbursedAdvanceId, new CashAdvanceBalanceCalculator.Parts(
                new BigDecimal("4500.00"), new BigDecimal("1000.00"), new BigDecimal("500.00"), new BigDecimal("3000.00"))));
        stubData(List.of(), List.of(), List.of());

        DashboardResponse r = service.employee();

        assertEquals(0, new BigDecimal("12000.00").compareTo(kpi(r, "caRequested")), "10,000 + 2,000; the draft is excluded");
        assertEquals(0, new BigDecimal("11000.00").compareTo(kpi(r, "caApproved")), "9,000 + 2,000");
        assertEquals(0, new BigDecimal("9000.00").compareTo(kpi(r, "caDisbursed")), "only the paid advance");
        assertEquals(0, new BigDecimal("4500.00").compareTo(kpi(r, "caUsedOnBills")));
        assertEquals(0, new BigDecimal("1000.00").compareTo(kpi(r, "caReturned")));
        assertEquals(0, new BigDecimal("500.00").compareTo(kpi(r, "caCarriedForward")));
        assertEquals(0, new BigDecimal("6000.00").compareTo(kpi(r, "caSettled")), "used 4,500 + returned 1,000 + carried 500");
        assertEquals(0, new BigDecimal("3000.00").compareTo(kpi(r, "caOutstanding")));
        assertEquals(0, BigDecimal.ZERO.compareTo(kpi(r, "caRecordGap")), "received = settled + outstanding");
    }

    /** Direct reports and reports against an advance are counted in separate flows, and the flows add up to the totals. */
    @Test
    void reimbursement_splitsDirectAndAdvanceFlows_andTheyAddUpToTotals() {
        // Direct: 1,000 approved, paid to the bank.
        ExpenseReport direct = report("ER-DIR", ReportStatus.CLOSED, PaymentRoutingStatus.PAYMENT_COMPLETED, "1000.00", "1000.00");
        // Against an advance: 1,500 approved, 1,200 paid from the advance, 300 extra sent to AP.
        ExpenseReport advance = report("ER-ADV", ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT, "300.00", null);
        advance.setCashAdvance(CashAdvance.builder().advanceId(UUID.randomUUID()).build());
        stubData(List.of(direct, advance),
                List.<Object[]>of(new Object[]{LocalDate.now(), new BigDecimal("1000.00"), "Travel"},
                        new Object[]{LocalDate.now(), new BigDecimal("1500.00"), "Travel"}),
                List.<Object[]>of(new Object[]{direct.getReportId(), new BigDecimal("1000.00")},
                        new Object[]{advance.getReportId(), new BigDecimal("1500.00")}));
        when(cashAdvanceRepository.findByEmployeeId(ME)).thenReturn(List.of());

        DashboardResponse r = service.employee();

        assertEquals(0, new BigDecimal("1000.00").compareTo(kpi(r, "drApproved")));
        assertEquals(0, new BigDecimal("1000.00").compareTo(kpi(r, "drPaid")));
        assertEquals(0, BigDecimal.ZERO.compareTo(kpi(r, "drAdvanceOffset")));
        assertEquals(0, new BigDecimal("1500.00").compareTo(kpi(r, "avApproved")));
        assertEquals(0, new BigDecimal("1200.00").compareTo(kpi(r, "avAdvanceOffset")));
        assertEquals(0, new BigDecimal("300.00").compareTo(kpi(r, "avPendingAp")));
        assertEquals(0, new BigDecimal("300.00").compareTo(kpi(r, "avPayable")));
        for (String k : List.of("Claimed", "Approved", "AdvanceOffset", "Payable", "ToSendAp", "PendingAp", "Paid")) {
            assertEquals(0, kpi(r, "er" + k).compareTo(kpi(r, "dr" + k).add(kpi(r, "av" + k))), "er" + k + " = direct + advance");
        }
    }

    /** Paid-out amount recorded lower than the advance amount: the gap is reported, never folded into another figure. */
    @Test
    void cashAdvance_paidOutRecordedLowerThanAmount_reportsRecordGap() {
        CashAdvance a = CashAdvance.builder().advanceId(disbursedAdvanceId).employeeId(ME)
                .amount(new BigDecimal("10000.00")).approvedAmount(new BigDecimal("10000.00"))
                .disbursedAmount(new BigDecimal("8500.00")).disbursedAt(LocalDateTime.now()).status("DISBURSED").build();
        when(cashAdvanceRepository.findByEmployeeId(ME)).thenReturn(List.of(a));
        // Calculator starts from the amount (10,000): 4,000 used, 6,000 outstanding.
        when(balanceCalculator.partsFor(any())).thenReturn(Map.of(disbursedAdvanceId, new CashAdvanceBalanceCalculator.Parts(
                new BigDecimal("4000.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("6000.00"))));
        stubData(List.of(), List.of(), List.of());

        DashboardResponse r = service.employee();

        assertEquals(0, new BigDecimal("4000.00").compareTo(kpi(r, "caSettled")), "settled is what the records say, not received - outstanding");
        assertEquals(0, new BigDecimal("-1500.00").compareTo(kpi(r, "caRecordGap")), "8,500 received - 4,000 - 6,000");
    }

    /** "Where your reports are": every report the employee can follow is in one step, so the steps add up. */
    @Test
    void journey_putsEveryReportInOneStep_includingSettledBySAdvance_andSentBack() {
        ExpenseReport draft = report("ER-D", ReportStatus.DRAFT, PaymentRoutingStatus.NONE, null, null);
        ExpenseReport submitted = report("ER-S", ReportStatus.SUBMITTED, PaymentRoutingStatus.NONE, null, null);
        ExpenseReport withManager = report("ER-M", ReportStatus.PENDING_APPROVAL, PaymentRoutingStatus.NONE, null, null);
        ExpenseReport queried = report("ER-Q", ReportStatus.QUERY_RAISED, PaymentRoutingStatus.NONE, null, null);
        ExpenseReport atFinance = report("ER-F", ReportStatus.PENDING_FINANCE_VERIFICATION, PaymentRoutingStatus.NONE, null, null);
        // Approved, 700 still owed, not routed to AP yet: on its way to payment, not settled.
        ExpenseReport owed = report("ER-O", ReportStatus.APPROVED, PaymentRoutingStatus.NONE, "700.00", null);
        ExpenseReport withAp = report("ER-A", ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT, "500.00", null);
        ExpenseReport paid = report("ER-P", ReportStatus.CLOSED, PaymentRoutingStatus.PAYMENT_COMPLETED, "400.00", "400.00");
        // Fully covered by the advance: nothing to pay. These were missing from the journey before.
        ExpenseReport covered1 = againstAdvance(report("ER-C1", ReportStatus.APPROVED, PaymentRoutingStatus.NONE, "0.00", null));
        ExpenseReport covered2 = againstAdvance(report("ER-C2", ReportStatus.CLOSED, PaymentRoutingStatus.NONE, "0.00", null));
        // Nothing payable and no advance involved: not "covered by advance", just no payment due.
        ExpenseReport nothingDue = report("ER-N", ReportStatus.APPROVED, PaymentRoutingStatus.NONE, "0.00", null);
        ExpenseReport rejected = report("ER-R", ReportStatus.REJECTED, PaymentRoutingStatus.NONE, null, null);
        stubData(List.of(draft, submitted, withManager, queried, atFinance, owed, withAp, paid, covered1, covered2, nothingDue, rejected),
                List.of(), List.of());
        when(cashAdvanceRepository.findByEmployeeId(ME)).thenReturn(List.of());

        DashboardResponse r = service.employee();

        java.util.Map<String, Long> steps = r.pipeline().stream()
                .collect(java.util.stream.Collectors.toMap(DashboardResponse.Stage::key, DashboardResponse.Stage::count));
        assertEquals(1L, steps.get("draft"));
        assertEquals(2L, steps.get("approval"), "submitted + with the manager");
        assertEquals(1L, steps.get("finance"));
        assertEquals(2L, steps.get("ap"), "with AP + approved and still owed");
        assertEquals(1L, steps.get("paid"));
        assertEquals(2L, steps.get("settled"), "covered by the linked advance, nothing to pay");
        assertEquals(1L, steps.get("noPayment"), "nothing payable, no advance involved");
        assertEquals(1L, steps.get("action"), "sent back to the employee");
        assertEquals(11L, steps.values().stream().mapToLong(Long::longValue).sum(), "all but the rejected report");
    }

    @Test
    void reimbursementChain_claimed_approved_coveredByAdvance_waitingForAp_paid() {
        ExpenseReport approved = againstAdvance(report("ER-1", ReportStatus.APPROVED, PaymentRoutingStatus.NONE, "700.00", null));
        ExpenseReport waiting = report("ER-2", ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT, "500.00", null);
        ExpenseReport paid = report("ER-3", ReportStatus.CLOSED, PaymentRoutingStatus.PAYMENT_COMPLETED, "400.00", "400.00");
        UUID id1 = approved.getReportId();
        UUID id2 = waiting.getReportId();
        UUID id3 = paid.getReportId();
        stubData(
                List.of(approved, waiting, paid),
                List.of(
                        new Object[]{LocalDate.now(), new BigDecimal("1000.00"), "Meals"},
                        new Object[]{LocalDate.now(), new BigDecimal("500.00"), "Travel"},
                        new Object[]{LocalDate.now(), new BigDecimal("400.00"), "Travel"}),
                List.<Object[]>of(new Object[]{id1, new BigDecimal("1000.00")},
                        new Object[]{id2, new BigDecimal("500.00")},
                        new Object[]{id3, new BigDecimal("400.00")}));
        when(cashAdvanceRepository.findByEmployeeId(ME)).thenReturn(List.of());

        DashboardResponse r = service.employee();

        assertEquals(0, new BigDecimal("1900.00").compareTo(kpi(r, "erClaimed")), "all claimed");
        assertEquals(0, new BigDecimal("1900.00").compareTo(kpi(r, "erApproved")), "all three reports are approved: 1000 + 500 + 400");
        assertEquals(0, new BigDecimal("300.00").compareTo(kpi(r, "erAdvanceOffset")), "ER-1: 1000 approved, 700 payable");
        assertEquals(0, new BigDecimal("500.00").compareTo(kpi(r, "erPendingAp")), "ER-2 still waiting for AP");
        assertEquals(0, new BigDecimal("400.00").compareTo(kpi(r, "erPaid")), "ER-3 paid");
        assertEquals(0, new BigDecimal("700.00").compareTo(kpi(r, "erToSendAp")), "ER-1 approved, not yet with AP");
        assertEquals(0, new BigDecimal("1600.00").compareTo(kpi(r, "erPayable")), "700 + 500 + 400");
        assertEquals(0, BigDecimal.ZERO.compareTo(kpi(r, "erNotApproved")), "everything claimed is approved here");
    }

    @Test
    void reimbursementChain_reconciles_approvedEqualsOffsetPlusPayable() {
        // approved = covered by advance + what is payable (waiting or paid), with nothing lost or counted twice
        ExpenseReport waiting = againstAdvance(report("ER-9", ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT, "800.00", null));
        UUID id = waiting.getReportId();
        stubData(List.of(waiting),
                List.<Object[]>of(new Object[]{LocalDate.now(), new BigDecimal("1000.00"), "Lodging"}),
                List.<Object[]>of(new Object[]{id, new BigDecimal("1000.00")}));
        when(cashAdvanceRepository.findByEmployeeId(ME)).thenReturn(List.of());

        DashboardResponse r = service.employee();

        BigDecimal approved = kpi(r, "erApproved");
        BigDecimal offset = kpi(r, "erAdvanceOffset");
        assertEquals(0, approved.compareTo(offset.add(kpi(r, "erPayable"))), "1000 = 200 offset + 800 payable");
        assertEquals(0, kpi(r, "erPayable").compareTo(kpi(r, "erToSendAp").add(kpi(r, "erPendingAp")).add(kpi(r, "erPaid"))),
                "payable = not yet with AP + waiting + paid");
    }

    /** A paid-out advance: {@code amount} = cash paid + carried in. */
    private static CashAdvance paidOut(String amount, String cash, String carriedIn, String carriedOut) {
        return CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId(ME).status("IN_PROGRESS")
                .amount(new BigDecimal(amount)).requestedAmount(new BigDecimal(cash)).approvedAmount(new BigDecimal(amount))
                .disbursedAmount(new BigDecimal(cash)).disbursedAt(LocalDateTime.now())
                .carriedInAmount(carriedIn == null ? null : new BigDecimal(carriedIn))
                .carriedOutAmount(carriedOut == null ? null : new BigDecimal(carriedOut))
                .build();
    }

    @Test
    void cashAdvanceLedger_countsCarriedInMoney_andFlagsCarryInWithNoSource() {
        // The case behind "Off by INR 1,518.00": 550 carried out of one advance, but 1,518 carried into another.
        CashAdvance pune = paidOut("4000", "4000", null, "550");    // 3,450 spent, 550 carried out, owes 0
        CashAdvance onsite = paidOut("5000", "5000", null, null);   // 4,032 spent, owes 968
        CashAdvance visit = paidOut("3518", "2000", "1518", null);  // 2,000 cash + 1,518 carried in, nothing spent
        when(cashAdvanceRepository.findByEmployeeId(ME)).thenReturn(List.of(pune, onsite, visit));
        when(balanceCalculator.rawOutstandingFor(any())).thenReturn(Map.of(
                pune.getAdvanceId(), BigDecimal.ZERO, onsite.getAdvanceId(), new BigDecimal("968"), visit.getAdvanceId(), new BigDecimal("3518")));
        stubData(List.of(), List.of(), List.of());

        DashboardResponse r = service.employee();

        assertEquals(0, new BigDecimal("11000").compareTo(kpi(r, "caDisbursed")), "cash paid out");
        assertEquals(0, new BigDecimal("1518").compareTo(kpi(r, "caCarriedIn")));
        assertEquals(0, new BigDecimal("8032").compareTo(kpi(r, "caSettled")), "4,000 + 4,032 + 0");
        assertEquals(0, new BigDecimal("4486").compareTo(kpi(r, "caOutstanding")));
        assertEquals(0, kpi(r, "caDisbursed").add(kpi(r, "caCarriedIn")).compareTo(kpi(r, "caSettled").add(kpi(r, "caOutstanding"))),
                "received + carried in = settled + outstanding");
        assertEquals(0, new BigDecimal("968").compareTo(kpi(r, "caCarryUnmatched")), "1,518 carried in, only 550 carried out");
    }

    @Test
    void carryForward_withAMatchingSource_hasNothingUnmatched() {
        CashAdvance source = paidOut("4000", "4000", null, "1518");
        CashAdvance destination = paidOut("3518", "2000", "1518", null);
        when(cashAdvanceRepository.findByEmployeeId(ME)).thenReturn(List.of(source, destination));
        when(balanceCalculator.rawOutstandingFor(any())).thenReturn(Map.of(source.getAdvanceId(), BigDecimal.ZERO, destination.getAdvanceId(), new BigDecimal("3518")));
        stubData(List.of(), List.of(), List.of());

        assertEquals(0, BigDecimal.ZERO.compareTo(kpi(service.employee(), "caCarryUnmatched")));
    }

    @Test
    void notPayableWithoutAnAdvance_isNoPaymentDue_notCoveredByAdvance() {
        // An approved report with nothing reimbursable and no linked advance (e.g. company-paid lines): 194.25 is not
        // "covered by advance". approved = covered by advance + no payment due + payable still holds.
        ExpenseReport noAdvance = report("ER-X", ReportStatus.APPROVED, PaymentRoutingStatus.NONE, "0.00", null);
        ExpenseReport covered = againstAdvance(report("ER-Y", ReportStatus.APPROVED, PaymentRoutingStatus.NONE, "0.00", null));
        stubData(List.of(noAdvance, covered),
                List.of(new Object[]{LocalDate.now(), new BigDecimal("194.25"), "Meals"},
                        new Object[]{LocalDate.now(), new BigDecimal("4032.00"), "Travel"}),
                List.<Object[]>of(new Object[]{noAdvance.getReportId(), new BigDecimal("194.25")},
                        new Object[]{covered.getReportId(), new BigDecimal("4032.00")}));
        when(cashAdvanceRepository.findByEmployeeId(ME)).thenReturn(List.of());

        DashboardResponse r = service.employee();

        assertEquals(0, new BigDecimal("4032.00").compareTo(kpi(r, "erAdvanceOffset")), "only the report linked to an advance");
        assertEquals(0, new BigDecimal("194.25").compareTo(kpi(r, "erNoPaymentDue")));
        assertEquals(0, kpi(r, "erApproved").compareTo(kpi(r, "erAdvanceOffset").add(kpi(r, "erNoPaymentDue")).add(kpi(r, "erPayable"))));
    }

    @Test
    void partlyPaidReport_countsThePaidPartAsPaid_andTheRemainderAsWaiting() {
        ExpenseReport partly = report("ER-7", ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT, "1000.00", "400.00");
        UUID id = partly.getReportId();
        stubData(List.of(partly),
                List.<Object[]>of(new Object[]{LocalDate.now(), new BigDecimal("1000.00"), "Travel"}),
                List.<Object[]>of(new Object[]{id, new BigDecimal("1000.00")}));
        when(cashAdvanceRepository.findByEmployeeId(ME)).thenReturn(List.of());

        DashboardResponse r = service.employee();

        assertEquals(0, new BigDecimal("600.00").compareTo(kpi(r, "erPendingAp")), "1000 owed less 400 already paid");
        assertEquals(0, new BigDecimal("400.00").compareTo(kpi(r, "erPaid")), "the paid part is not lost");
        assertEquals(0, new BigDecimal("1000.00").compareTo(kpi(r, "erPayable")), "waiting + paid = owed");
    }

    @Test
    void categoryDonut_addsUpToTheClaimedTotal() {
        stubData(List.of(),
                List.<Object[]>of(new Object[]{LocalDate.now(), new BigDecimal("600.00"), "Meals"},
                        new Object[]{LocalDate.now(), new BigDecimal("400.00"), "Travel"},
                        new Object[]{LocalDate.now(), new BigDecimal("250.00"), "Travel"}),
                List.of());
        when(cashAdvanceRepository.findByEmployeeId(ME)).thenReturn(List.of());

        DashboardResponse r = service.employee();

        BigDecimal donut = r.breakdown().stream().map(s -> s.amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, kpi(r, "erClaimed").compareTo(donut), "every claimed rupee is in one category slice");
        assertEquals("Reimbursement by category", r.breakdownTitle());
    }
}
