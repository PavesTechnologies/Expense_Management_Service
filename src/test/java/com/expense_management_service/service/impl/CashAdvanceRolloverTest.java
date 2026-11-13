package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceCarryForward;
import com.expense_management_service.repository.CashAdvanceCarryForwardRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The rollover itself: FIFO allocation, partial coverage, the revalidation of each balance under lock, duplicate
 * prevention, currency and status eligibility, and the overdue exception. Storage and balance calculation are mocked.
 */
class CashAdvanceRolloverTest {

    private static final String ME = "EMP001";
    private static final String FINANCE = "FIN1";

    private CashAdvanceRepository cashAdvanceRepository;
    private CashAdvanceCarryForwardRepository carryForwardRepository;
    private CashAdvanceBalanceCalculator balanceCalculator;
    private CashAdvanceBalanceLedger balanceLedger;
    private CashAdvanceCarryForwardService service;
    private Map<UUID, CashAdvance> byId;
    private List<CashAdvance> openAdvances;
    private CashAdvance destination;

    private static Currency inr() {
        return Currency.builder().currencyCode("INR").decimalPlaces(2).build();
    }

    private static CashAdvance advance(String status, String outstanding, LocalDateTime createdAt, Currency currency) {
        CashAdvance a = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId(ME)
                .amount(new BigDecimal(outstanding)).status(status).currency(currency).createdAt(createdAt).build();
        a.setOutstandingBalance(new BigDecimal(outstanding));
        return a;
    }

    @BeforeEach
    void setUp() {
        cashAdvanceRepository = mock(CashAdvanceRepository.class);
        carryForwardRepository = mock(CashAdvanceCarryForwardRepository.class);
        balanceCalculator = mock(CashAdvanceBalanceCalculator.class);
        balanceLedger = mock(CashAdvanceBalanceLedger.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        when(currentUserService.getEmployeeId()).thenReturn(FINANCE);

        service = new CashAdvanceCarryForwardService(cashAdvanceRepository, balanceCalculator, carryForwardRepository,
                balanceLedger, currentUserService);
        ReflectionTestUtils.setField(service, "enabled", true);

        byId = new HashMap<>();
        openAdvances = new java.util.ArrayList<>();
        when(cashAdvanceRepository.findByEmployeeIdAndStatusIn(eq(ME), anyList())).thenAnswer(inv -> openAdvances);
        when(cashAdvanceRepository.findByIdForUpdate(any())).thenAnswer(inv -> Optional.ofNullable(byId.get(inv.getArgument(0))));
        when(balanceCalculator.outstanding(any(CashAdvance.class)))
                .thenAnswer(inv -> ((CashAdvance) inv.getArgument(0)).getOutstandingBalance());
        when(carryForwardRepository.findBySourceAdvance_AdvanceIdAndStatusIn(any(), anyCollection())).thenReturn(List.of());
        when(carryForwardRepository.findByDestinationAdvance_AdvanceIdAndStatusIn(any(), anyCollection())).thenReturn(List.of());

        destination = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId(ME).amount(new BigDecimal("3000"))
                .status("PENDING_FINANCE_APPROVAL").currency(inr()).build();
        byId.put(destination.getAdvanceId(), destination);
    }

    private CashAdvance register(CashAdvance a) {
        byId.put(a.getAdvanceId(), a);
        openAdvances.add(a);
        return a;
    }

    private BigDecimal rollover(BigDecimal amount, CashAdvanceCarryForwardService.OverdueException overdue, boolean executive) {
        return service.applyRollover(destination, amount, new BigDecimal("2000"), overdue, FINANCE, "Rolled over on approval", executive);
    }

    // ------------------------------------------------------------------ FIFO and the amounts

    @Test
    void fifo_takesTheOldestAdvanceFirst_andRecordsOneRowPerSource() {
        CashAdvance older = register(advance("DISBURSED", "4000", LocalDateTime.of(2026, 9, 1, 9, 0), inr()));
        CashAdvance newer = register(advance("DISBURSED", "3000", LocalDateTime.of(2026, 10, 1, 9, 0), inr()));

        BigDecimal total = rollover(new BigDecimal("5000"), null, false);

        assertEquals(0, new BigDecimal("5000").compareTo(total));
        ArgumentCaptor<CashAdvanceCarryForward> rows = ArgumentCaptor.forClass(CashAdvanceCarryForward.class);
        verify(carryForwardRepository, times(2)).save(rows.capture());
        List<CashAdvanceCarryForward> saved = rows.getAllValues();
        assertEquals(older.getAdvanceId(), saved.get(0).getSourceAdvance().getAdvanceId());
        assertEquals(0, new BigDecimal("4000").compareTo(saved.get(0).getAmount()));
        assertEquals(newer.getAdvanceId(), saved.get(1).getSourceAdvance().getAdvanceId());
        assertEquals(0, new BigDecimal("1000").compareTo(saved.get(1).getAmount()));
        saved.forEach(r -> {
            assertEquals(CashAdvanceCarryForward.APPLIED, r.getStatus());
            assertEquals(ME, r.getRequestedBy(), "the employee is the requester");
            assertEquals(FINANCE, r.getApprovedBy(), "Finance is the approver");
            assertEquals(destination.getAdvanceId(), r.getDestinationAdvance().getAdvanceId());
            assertEquals("STANDARD", r.getAuthorizationType());
        });
    }

    @Test
    void theDestinationAmount_becomesCashPlusRollover_soDisbursementPaysOnlyTheCash() {
        register(advance("DISBURSED", "4000", LocalDateTime.of(2026, 9, 1, 9, 0), inr()));

        rollover(new BigDecimal("4000"), null, false);

        assertEquals(0, new BigDecimal("6000").compareTo(destination.getAmount()), "cash 2,000 + rolled 4,000");
        assertEquals(0, new BigDecimal("4000").compareTo(destination.getCarriedInAmount()));
        assertEquals(0, new BigDecimal("4000").compareTo(destination.getAuthorizedRolloverAmount()));
        // disbursement pays amount minus carried-in: 6,000 - 4,000 = 2,000 cash, not 6,000
        assertEquals(0, new BigDecimal("2000").compareTo(destination.getAmount().subtract(destination.getCarriedInAmount())));
    }

    @Test
    void theSourceBalance_isReducedByExactlyWhatWasRolled() {
        CashAdvance source = register(advance("DISBURSED", "4000", LocalDateTime.of(2026, 9, 1, 9, 0), inr()));

        rollover(new BigDecimal("1500"), null, false);

        assertEquals(0, new BigDecimal("1500").compareTo(source.getCarriedOutAmount()));
        verify(balanceLedger).applyBalance(eq(source), any(), eq("CASH_ADVANCE_CARRIED_FORWARD_OUT"), any(), any());
    }

    // ------------------------------------------------------------------ partial coverage and amounts

    @Test
    void partialCoverage_acrossSources_isAllowedWhenTheTotalIsAvailable() {
        register(advance("DISBURSED", "2000", LocalDateTime.of(2026, 9, 1, 9, 0), inr()));
        register(advance("PARTIALLY_SETTLED", "2500", LocalDateTime.of(2026, 9, 5, 9, 0), inr()));
        assertDoesNotThrow(() -> rollover(new BigDecimal("4500"), null, false));
    }

    @Test
    void refusesARolloverLargerThanTheEligibleBalance_andNamesWhatIsAvailable() {
        register(advance("DISBURSED", "4000", LocalDateTime.of(2026, 9, 1, 9, 0), inr()));
        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class,
                () -> rollover(new BigDecimal("5000"), null, false));
        assertTrue(ex.getMessage().contains("Only 4000"), ex.getMessage());
        verify(carryForwardRepository, never()).save(any());
    }

    @Test
    void refusesAZeroOrNegativeRollover() {
        assertThrows(BusinessRuleViolationException.class, () -> rollover(BigDecimal.ZERO, null, false));
        assertThrows(BusinessRuleViolationException.class, () -> rollover(new BigDecimal("-100"), null, false));
    }

    @Test
    void refusesANegativeCashAmount() {
        assertThrows(BusinessRuleViolationException.class, () -> service.applyRollover(destination, new BigDecimal("100"),
                new BigDecimal("-1"), null, FINANCE, "r", false));
    }

    // ------------------------------------------------------------------ eligibility

    @Test
    void excludesExpensesAwaitingApproval_andUnverifiedStatuses() {
        register(advance("EXPENSE_SUBMITTED", "9000", LocalDateTime.of(2026, 8, 1, 9, 0), inr()));
        register(advance("EXPENSE_VERIFIED", "9000", LocalDateTime.of(2026, 8, 1, 9, 0), inr()));
        register(advance("DISBURSED", "1000", LocalDateTime.of(2026, 9, 1, 9, 0), inr()));

        // Only the disbursed 1,000 counts; the 18,000 awaiting approval is not rolled over.
        assertThrows(BusinessRuleViolationException.class, () -> rollover(new BigDecimal("2000"), null, false));
        assertDoesNotThrow(() -> rollover(new BigDecimal("1000"), null, false));
    }

    @Test
    void excludesSourcesInAnotherCurrency() {
        Currency usd = Currency.builder().currencyCode("USD").decimalPlaces(2).build();
        register(advance("DISBURSED", "4000", LocalDateTime.of(2026, 9, 1, 9, 0), usd));
        assertThrows(BusinessRuleViolationException.class, () -> rollover(new BigDecimal("1000"), null, false));
    }

    @Test
    void refusesAnAdvanceThatIsNotAnEligibleStatus_evenIfItIsOwed() {
        register(advance("APPROVED", "4000", LocalDateTime.of(2026, 9, 1, 9, 0), inr()));
        assertThrows(BusinessRuleViolationException.class, () -> rollover(new BigDecimal("1000"), null, false));
    }

    // ------------------------------------------------------------------ revalidation and duplicates

    @Test
    void revalidatesEachBalance_underLock_notFromAnEarlierFigure() {
        CashAdvance source = register(advance("DISBURSED", "4000", LocalDateTime.of(2026, 9, 1, 9, 0), inr()));
        // The ledger now says 1,000 is left (for example, an offset was approved after the request was made).
        when(balanceCalculator.outstanding(source)).thenReturn(new BigDecimal("1000"));
        assertThrows(BusinessRuleViolationException.class, () -> rollover(new BigDecimal("2000"), null, false));
    }

    @Test
    void subtractsWhatIsAlreadyAllocatedFromTheSource() {
        CashAdvance source = register(advance("DISBURSED", "4000", LocalDateTime.of(2026, 9, 1, 9, 0), inr()));
        CashAdvanceCarryForward earlier = CashAdvanceCarryForward.builder().amount(new BigDecimal("3500"))
                .status(CashAdvanceCarryForward.APPLIED).build();
        when(carryForwardRepository.findBySourceAdvance_AdvanceIdAndStatusIn(eq(source.getAdvanceId()), anyCollection()))
                .thenReturn(List.of(earlier));
        assertThrows(BusinessRuleViolationException.class, () -> rollover(new BigDecimal("1000"), null, false));
    }

    @Test
    void refusesASecondRolloverOntoTheSameRequest() {
        register(advance("DISBURSED", "4000", LocalDateTime.of(2026, 9, 1, 9, 0), inr()));
        when(carryForwardRepository.findByDestinationAdvance_AdvanceIdAndStatusIn(eq(destination.getAdvanceId()), anyCollection()))
                .thenReturn(List.of(CashAdvanceCarryForward.builder().amount(BigDecimal.ONE).build()));
        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class, () -> rollover(new BigDecimal("1000"), null, false));
        assertTrue(ex.getMessage().contains("cannot be applied twice"), ex.getMessage());
    }

    @Test
    void refusesARolloverOntoARequestThatIsAlreadyDisbursed() {
        destination.setDisbursedAt(LocalDateTime.now());
        register(advance("DISBURSED", "4000", LocalDateTime.of(2026, 9, 1, 9, 0), inr()));
        assertThrows(BusinessRuleViolationException.class, () -> rollover(new BigDecimal("1000"), null, false));
    }

    // ------------------------------------------------------------------ feature flag

    @Test
    void refusesEverythingWhileTheCarryForwardFeatureIsOff() {
        ReflectionTestUtils.setField(service, "enabled", false);
        register(advance("DISBURSED", "4000", LocalDateTime.of(2026, 9, 1, 9, 0), inr()));
        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class, () -> rollover(new BigDecimal("1000"), null, false));
        assertTrue(ex.getMessage().contains("disabled"), ex.getMessage());
        verifyNoInteractions(carryForwardRepository);
    }

    // ------------------------------------------------------------------ overdue exception

    @Test
    void overdueSource_needsAFinanceExecutive() {
        CashAdvance overdue = register(advance("OVERDUE", "3000", LocalDateTime.of(2026, 6, 1, 9, 0), inr()));
        var exception = new CashAdvanceCarryForwardService.OverdueException(overdue.getAdvanceId(), new BigDecimal("2000"), "Client trip cannot wait");
        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class, () -> rollover(new BigDecimal("2000"), exception, false));
        assertTrue(ex.getMessage().contains("Finance executive"), ex.getMessage());
    }

    @Test
    void overdueSource_withAnExecutive_isRecordedAsAnException_withTheReason() {
        CashAdvance overdue = register(advance("OVERDUE", "3000", LocalDateTime.of(2026, 6, 1, 9, 0), inr()));
        var exception = new CashAdvanceCarryForwardService.OverdueException(overdue.getAdvanceId(), new BigDecimal("2000"), "Client trip cannot wait");

        rollover(new BigDecimal("2000"), exception, true);

        ArgumentCaptor<CashAdvanceCarryForward> rows = ArgumentCaptor.forClass(CashAdvanceCarryForward.class);
        verify(carryForwardRepository).save(rows.capture());
        CashAdvanceCarryForward row = rows.getValue();
        assertEquals("OVERDUE_EXCEPTION", row.getAuthorizationType());
        assertEquals("Client trip cannot wait", row.getExceptionJustification());
        assertEquals(FINANCE, row.getExceptionAuthorizedBy());
        assertEquals(0, new BigDecimal("2000").compareTo(row.getAmount()), "capped at the authorised amount");
    }

    @Test
    void overdueSource_withoutAJustification_isRefused() {
        CashAdvance overdue = register(advance("OVERDUE", "3000", LocalDateTime.of(2026, 6, 1, 9, 0), inr()));
        var exception = new CashAdvanceCarryForwardService.OverdueException(overdue.getAdvanceId(), new BigDecimal("2000"), " ");
        assertThrows(BusinessRuleViolationException.class, () -> rollover(new BigDecimal("2000"), exception, true));
    }

    @Test
    void overdueSource_isUsedOnlyIfItsAmountIsAuthorised_andNotOtherwise() {
        register(advance("OVERDUE", "3000", LocalDateTime.of(2026, 6, 1, 9, 0), inr()));
        // An OVERDUE advance is not a source without an exception, even when it is the only one owed.
        assertThrows(BusinessRuleViolationException.class, () -> rollover(new BigDecimal("1000"), null, false));
    }

    @Test
    void overdueException_namingAnAdvanceThatIsNotOwedByThisEmployee_isRefused() {
        var exception = new CashAdvanceCarryForwardService.OverdueException(UUID.randomUUID(), new BigDecimal("2000"), "Reason");
        assertThrows(BusinessRuleViolationException.class, () -> rollover(new BigDecimal("1000"), exception, true));
    }

    @Test
    void overdueException_isRefusedWhenTheRolloverDoesNotNeedIt() {
        // The overdue advance is the newer one, so first-in-first-out gives the 1,000 to the older disbursed advance.
        CashAdvance overdue = register(advance("OVERDUE", "3000", LocalDateTime.of(2026, 10, 1, 9, 0), inr()));
        register(advance("DISBURSED", "4000", LocalDateTime.of(2026, 9, 1, 9, 0), inr()));
        var exception = new CashAdvanceCarryForwardService.OverdueException(overdue.getAdvanceId(), new BigDecimal("2000"), "Reason");
        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class,
                () -> rollover(new BigDecimal("1000"), exception, true));
        assertTrue(ex.getMessage().contains("not needed"), ex.getMessage());
    }
}
