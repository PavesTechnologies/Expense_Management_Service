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
        when(balanceCalculator.rawOutstandingFor(any())).thenReturn(Map.of(disbursedAdvanceId, new BigDecimal("3000.00")));
        stubData(List.of(), List.of(), List.of());

        DashboardResponse r = service.employee();

        assertEquals(0, new BigDecimal("12000.00").compareTo(kpi(r, "caRequested")), "10,000 + 2,000; the draft is excluded");
        assertEquals(0, new BigDecimal("11000.00").compareTo(kpi(r, "caApproved")), "9,000 + 2,000");
        assertEquals(0, new BigDecimal("9000.00").compareTo(kpi(r, "caDisbursed")), "only the paid advance");
        assertEquals(0, new BigDecimal("6000.00").compareTo(kpi(r, "caSettled")), "disbursed 9,000 less outstanding 3,000");
        assertEquals(0, new BigDecimal("3000.00").compareTo(kpi(r, "caOutstanding")));
    }

    @Test
    void reimbursementChain_claimed_approved_coveredByAdvance_waitingForAp_paid() {
        ExpenseReport approved = report("ER-1", ReportStatus.APPROVED, PaymentRoutingStatus.NONE, "700.00", null);
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
        ExpenseReport waiting = report("ER-9", ReportStatus.APPROVED, PaymentRoutingStatus.APPROVED_FOR_PAYMENT, "800.00", null);
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
