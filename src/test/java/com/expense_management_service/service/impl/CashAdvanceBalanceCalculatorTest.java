package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceAdjustment;
import com.expense_management_service.entity.CashAdvanceRepayment;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.repository.CashAdvanceAdjustmentRepository;
import com.expense_management_service.repository.CashAdvanceRepaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CashAdvanceBalanceCalculatorTest {

    @Mock private CashAdvanceAdjustmentRepository adjustmentRepository;
    @Mock private CashAdvanceRepaymentRepository repaymentRepository;

    private CashAdvanceBalanceCalculator calculator() {
        return new CashAdvanceBalanceCalculator(adjustmentRepository, repaymentRepository);
    }

    private static Currency inr() {
        Currency c = new Currency();
        c.setDecimalPlaces(2);
        return c;
    }

    private static CashAdvance advance(UUID id, String amount, String stored) {
        return CashAdvance.builder()
                .advanceId(id)
                .employeeId("EMP001")
                .amount(new BigDecimal(amount))
                .outstandingBalance(stored == null ? null : new BigDecimal(stored))
                .currency(inr())
                .status("DISBURSED")
                .build();
    }

    private static CashAdvanceAdjustment offset(CashAdvance a, String amount, boolean reversed) {
        return CashAdvanceAdjustment.builder()
                .adjustmentId(UUID.randomUUID())
                .cashAdvance(a)
                .adjustedAmount(new BigDecimal(amount))
                .reversed(reversed)
                .build();
    }

    private static CashAdvanceRepayment repayment(CashAdvance a, String amount) {
        return CashAdvanceRepayment.builder().cashAdvance(a).amount(new BigDecimal(amount)).build();
    }

    @Test
    void outstanding_countsOnlyActiveOffsets_andRepayments() {
        UUID id = UUID.randomUUID();
        CashAdvance a = advance(id, "1000.00", "600.00");
        when(adjustmentRepository.findByCashAdvance_AdvanceId(id)).thenReturn(List.of(
                offset(a, "300.00", false),
                offset(a, "200.00", true)));   // reversed: must not count
        when(repaymentRepository.findByCashAdvance_AdvanceId(id)).thenReturn(List.of(repayment(a, "100.00")));

        // 1000 - 300 (active) - 100 (repaid) = 600
        assertEquals(0, new BigDecimal("600.00").compareTo(calculator().outstanding(a)));
    }

    @Test
    void outstanding_reversedOffsetOnly_leavesTheFullAmount() {
        UUID id = UUID.randomUUID();
        CashAdvance a = advance(id, "1000.00", "1000.00");
        when(adjustmentRepository.findByCashAdvance_AdvanceId(id)).thenReturn(List.of(offset(a, "400.00", true)));
        when(repaymentRepository.findByCashAdvance_AdvanceId(id)).thenReturn(List.of());

        assertEquals(0, new BigDecimal("1000.00").compareTo(calculator().outstanding(a)));
    }

    @Test
    void outstanding_neverNegative_isRejectedNotSilentlyClamped() {
        UUID id = UUID.randomUUID();
        CashAdvance a = advance(id, "1000.00", "0.00");
        when(adjustmentRepository.findByCashAdvance_AdvanceId(id)).thenReturn(List.of(offset(a, "1200.00", false)));
        when(repaymentRepository.findByCashAdvance_AdvanceId(id)).thenReturn(List.of());

        assertThrows(BusinessRuleViolationException.class, () -> calculator().outstanding(a));
    }

    @Test
    void activeOffsets_andRepaymentTotal_treatNullListsAsZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(CashAdvanceBalanceCalculator.activeOffsets(null)));
        assertEquals(0, BigDecimal.ZERO.compareTo(CashAdvanceBalanceCalculator.repaymentTotal(null)));
    }

    @Test
    void findDrift_reportsOnlyMismatches_withAdvanceIdStoredCalculatedAndDifference() {
        UUID ok = UUID.randomUUID();
        UUID drifted = UUID.randomUUID();
        UUID nullStored = UUID.randomUUID();
        CashAdvance okAdv = advance(ok, "1000.00", "700.00");
        CashAdvance driftAdv = advance(drifted, "1000.00", "900.00");   // records say 700
        CashAdvance nullAdv = advance(nullStored, "500.00", null);      // no cached value at all

        when(adjustmentRepository.findByCashAdvance_AdvanceIdIn(any())).thenReturn(List.of(
                offset(okAdv, "300.00", false), offset(driftAdv, "300.00", false)));
        when(repaymentRepository.findByCashAdvance_AdvanceIdIn(any())).thenReturn(List.of());

        List<CashAdvanceBalanceCalculator.Drift> found = calculator().findDrift(List.of(okAdv, driftAdv, nullAdv));

        assertEquals(2, found.size());
        CashAdvanceBalanceCalculator.Drift d = found.stream().filter(x -> x.advanceId().equals(drifted)).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("900.00").compareTo(d.stored()));
        assertEquals(0, new BigDecimal("700.00").compareTo(d.computed()));
        assertEquals(0, new BigDecimal("200.00").compareTo(d.difference()), "stored minus calculated");
        CashAdvanceBalanceCalculator.Drift n = found.stream().filter(x -> x.advanceId().equals(nullStored)).findFirst().orElseThrow();
        assertNull(n.stored());
        assertNull(n.difference());
        assertFalse(found.stream().anyMatch(x -> x.advanceId().equals(ok)));
    }

    @Test
    void rawOutstandingFor_usesTwoBatchQueries_forAnyNumberOfAdvances() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        CashAdvance advA = advance(a, "1000.00", null);
        CashAdvance advB = advance(b, "500.00", null);
        when(adjustmentRepository.findByCashAdvance_AdvanceIdIn(any())).thenReturn(List.of(offset(advA, "100.00", false), offset(advA, "50.00", true)));
        when(repaymentRepository.findByCashAdvance_AdvanceIdIn(any())).thenReturn(List.of(repayment(advB, "200.00")));

        java.util.Map<UUID, BigDecimal> out = calculator().rawOutstandingFor(List.of(advA, advB));

        assertEquals(0, new BigDecimal("900.00").compareTo(out.get(a)), "reversed 50 excluded");
        assertEquals(0, new BigDecimal("300.00").compareTo(out.get(b)));
        verify(adjustmentRepository, times(1)).findByCashAdvance_AdvanceIdIn(any());
        verify(repaymentRepository, times(1)).findByCashAdvance_AdvanceIdIn(any());
        verify(adjustmentRepository, never()).findByCashAdvance_AdvanceId(any());
    }

    @Test
    void rawOutstanding_reportsANegativeValueInsteadOfHidingIt() {
        UUID id = UUID.randomUUID();
        CashAdvance a = advance(id, "1000.00", "0.00");
        when(adjustmentRepository.findByCashAdvance_AdvanceId(id)).thenReturn(List.of(offset(a, "1200.00", false)));
        when(repaymentRepository.findByCashAdvance_AdvanceId(id)).thenReturn(List.of());

        assertEquals(0, new BigDecimal("-200.00").compareTo(calculator().rawOutstanding(a)));
    }
}
