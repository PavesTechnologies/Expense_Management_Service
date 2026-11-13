package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.DashboardResponse;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.enums.ReportStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AP dashboard "Amount to pay": only the unpaid remainder of each report, never the owed or paid amounts, and no paid
 * amount is counted twice. Checks the KPI and the attention list against the same calculation.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class DashboardApAmountToPayTest {

    @InjectMocks
    private DashboardServiceImpl service;

    private EntityManager em;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        em = mock(EntityManager.class);
        ReflectionTestUtils.setField(service, "em", em);
        ReflectionTestUtils.setField(service, "baseCurrencyCode", "INR");
        // paid-side query: nothing paid yet in these scenarios
        TypedQuery<ExpenseReport> none = mock(TypedQuery.class, RETURNS_SELF);
        when(none.getResultList()).thenReturn(List.of());
        when(em.createQuery(contains("r.paymentRoutingStatus = :completed"), eq(ExpenseReport.class))).thenReturn(none);
        TypedQuery<Long> count = mock(TypedQuery.class, RETURNS_SELF);
        when(count.getSingleResult()).thenReturn(0L);
        when(em.createQuery(anyString(), eq(Long.class))).thenReturn(count);
    }

    /** Stubs the to-pay list and the gross line totals for the given reports. */
    @SuppressWarnings("unchecked")
    private void stub(List<ExpenseReport> toPay, List<Object[]> grossRows) {
        TypedQuery<ExpenseReport> toPayQuery = mock(TypedQuery.class, RETURNS_SELF);
        when(toPayQuery.getResultList()).thenReturn(toPay);
        when(em.createQuery(contains("r.reportStatus = :approved"), eq(ExpenseReport.class))).thenReturn(toPayQuery);
        TypedQuery<Object[]> lines = mock(TypedQuery.class, RETURNS_SELF);
        when(lines.getResultList()).thenReturn(grossRows);
        when(em.createQuery(contains("sum(l.baseAmount)"), eq(Object[].class))).thenReturn(lines);
    }

    private static ExpenseReport report(UUID id, String owed, String paid) {
        return ExpenseReport.builder()
                .reportId(id)
                .reportNumber("ER-" + id.toString().substring(0, 4))
                .employeeId("EMP001")
                .title("Trip")
                .reportStatus(ReportStatus.APPROVED)
                .paymentRoutingStatus(PaymentRoutingStatus.APPROVED_FOR_PAYMENT)
                .approvedAt(LocalDateTime.now().minusDays(2))
                .totalAmount(new BigDecimal(owed))
                .reimbursableAmount(new BigDecimal(owed))
                .paidAmount(paid == null ? null : new BigDecimal(paid))
                .build();
    }

    private static BigDecimal amountToPay(DashboardResponse response) {
        return response.kpis().stream().filter(k -> "toPayValue".equals(k.key())).findFirst().orElseThrow().value();
    }

    // ------------------------------------------------------------------ the three cases the brief names

    @Test
    void partiallyPaidReport_showsOnlyTheRemainingPayable() {
        UUID id = UUID.randomUUID();
        stub(List.of(report(id, "1000.00", "400.00")), List.<Object[]>of(new Object[]{id, new BigDecimal("1000.00")}));

        DashboardResponse response = service.ap();

        assertEquals(0, new BigDecimal("600.00").compareTo(amountToPay(response)), "1000 owed, 400 paid: 600 left");
    }

    @Test
    void fullyPaidReport_showsZeroOutstanding() {
        UUID id = UUID.randomUUID();
        stub(List.of(report(id, "1000.00", "1000.00")), List.<Object[]>of(new Object[]{id, new BigDecimal("1000.00")}));

        DashboardResponse response = service.ap();

        assertEquals(0, BigDecimal.ZERO.compareTo(amountToPay(response)), "nothing left to pay");
    }

    @Test
    void unpaidReport_showsTheFullOwedAmount() {
        UUID id = UUID.randomUUID();
        stub(List.of(report(id, "1000.00", null)), List.<Object[]>of(new Object[]{id, new BigDecimal("1000.00")}));

        DashboardResponse response = service.ap();

        assertEquals(0, new BigDecimal("1000.00").compareTo(amountToPay(response)));
    }

    // ------------------------------------------------------------------ no double counting

    @Test
    void severalReports_areSummedOnTheirRemainders_andPaidAmountsAreNotCountedAgain() {
        UUID partial = UUID.randomUUID();
        UUID unpaid = UUID.randomUUID();
        stub(List.of(report(partial, "1000.00", "400.00"), report(unpaid, "500.00", null)),
                List.of(new Object[]{partial, new BigDecimal("1000.00")}, new Object[]{unpaid, new BigDecimal("500.00")}));

        DashboardResponse response = service.ap();

        // 600 remaining + 500 unpaid. The 400 already paid is not added back, and 1000 is not counted as owed.
        assertEquals(0, new BigDecimal("1100.00").compareTo(amountToPay(response)));
    }

    @Test
    void attentionList_showsTheSameRemainingAmountAsTheKpi() {
        UUID partial = UUID.randomUUID();
        stub(List.of(report(partial, "1000.00", "400.00")), List.<Object[]>of(new Object[]{partial, new BigDecimal("1000.00")}));

        DashboardResponse response = service.ap();

        assertEquals(1, response.attention().size());
        assertEquals(0, new BigDecimal("600.00").compareTo(response.attention().get(0).amount()),
                "the list and the KPI must agree");
    }

    @Test
    void reimbursableFallsBackToTheGrossLines_whenItIsNotSet() {
        UUID id = UUID.randomUUID();
        ExpenseReport r = report(id, "1000.00", "250.00");
        r.setReimbursableAmount(null);
        stub(List.of(r), List.<Object[]>of(new Object[]{id, new BigDecimal("900.00")}));

        DashboardResponse response = service.ap();

        assertEquals(0, new BigDecimal("650.00").compareTo(amountToPay(response)), "gross 900 less paid 250");
    }

    // ------------------------------------------------------------------ the helpers the calculation uses

    @Test
    void remainingPayable_isNeverNegative_andTreatsMissingPaidAsZero() {
        assertEquals(0, new BigDecimal("600.00").compareTo(DashboardServiceImpl.remainingPayable(new BigDecimal("1000.00"), new BigDecimal("400.00"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(DashboardServiceImpl.remainingPayable(new BigDecimal("1000.00"), new BigDecimal("1200.00"))));
        assertEquals(0, new BigDecimal("1000.00").compareTo(DashboardServiceImpl.remainingPayable(new BigDecimal("1000.00"), null)));
        assertEquals(0, BigDecimal.ZERO.compareTo(DashboardServiceImpl.remainingPayable(null, null)));
    }
}
