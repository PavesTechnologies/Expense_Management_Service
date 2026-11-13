package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceCarryForward;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.repository.CashAdvanceCarryForwardRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.RoleConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Compensating reversal of a rollover after its request is disbursed: who may propose and approve, the balance movements,
 * the cash that must not change, and the refusals.
 */
class CashAdvanceCompensationTest {

    private static final String EMPLOYEE = "EMP001";
    private static final String EXEC_A = "EXEC-A";
    private static final String EXEC_B = "EXEC-B";

    private CashAdvanceRepository cashAdvanceRepository;
    private CashAdvanceCarryForwardRepository carryForwardRepository;
    private CashAdvanceBalanceCalculator balanceCalculator;
    private CashAdvanceBalanceLedger balanceLedger;
    private CurrentUserService currentUserService;
    private CashAdvanceCarryForwardService service;

    private CashAdvance source;
    private CashAdvance destination;
    private CashAdvanceCarryForward original;
    private final Map<UUID, CashAdvanceCarryForward> rows = new HashMap<>();
    private final Map<UUID, CashAdvance> advances = new HashMap<>();

    private static void signInAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("x", null,
                List.of(new SimpleGrantedAuthority(role))));
    }

    @BeforeEach
    void setUp() {
        cashAdvanceRepository = mock(CashAdvanceRepository.class);
        carryForwardRepository = mock(CashAdvanceCarryForwardRepository.class);
        balanceCalculator = mock(CashAdvanceBalanceCalculator.class);
        balanceLedger = mock(CashAdvanceBalanceLedger.class);
        currentUserService = mock(CurrentUserService.class);
        service = new CashAdvanceCarryForwardService(cashAdvanceRepository, balanceCalculator, carryForwardRepository,
                balanceLedger, currentUserService);
        ReflectionTestUtils.setField(service, "enabled", false); // compensation does not need the feature flag

        Currency inr = Currency.builder().currencyCode("INR").decimalPlaces(2).build();
        source = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId(EMPLOYEE).amount(new BigDecimal("4000"))
                .status("DISBURSED").currency(inr).createdAt(LocalDateTime.of(2026, 9, 1, 9, 0)).build();
        source.setCarriedOutAmount(new BigDecimal("4000"));
        source.setOutstandingBalance(BigDecimal.ZERO);
        destination = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId(EMPLOYEE).amount(new BigDecimal("6000"))
                .status("DISBURSED").currency(inr).disbursedAt(LocalDateTime.now()).build();
        destination.setCarriedInAmount(new BigDecimal("4000"));
        destination.setDisbursedAmount(new BigDecimal("2000"));
        destination.setAuthorizedRolloverAmount(new BigDecimal("4000"));
        advances.put(source.getAdvanceId(), source);
        advances.put(destination.getAdvanceId(), destination);

        original = CashAdvanceCarryForward.builder().carryForwardId(UUID.randomUUID()).sourceAdvance(source)
                .destinationAdvance(destination).employeeId(EMPLOYEE).currencyCode("INR").amount(new BigDecimal("4000"))
                .status(CashAdvanceCarryForward.APPLIED).reason("Rolled over").idempotencyKey("rollover:x")
                .requestedBy(EMPLOYEE).requestedAt(LocalDateTime.now()).approvedBy(EXEC_A).build();
        rows.put(original.getCarryForwardId(), original);

        when(cashAdvanceRepository.findByIdForUpdate(any())).thenAnswer(inv -> Optional.ofNullable(advances.get(inv.getArgument(0))));
        when(cashAdvanceRepository.save(any(CashAdvance.class))).thenAnswer(inv -> inv.getArgument(0));
        when(carryForwardRepository.findByIdForUpdate(any())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.getArgument(0))));
        when(carryForwardRepository.findByReversesCarryForwardIdAndStatusIn(any(), anyCollection())).thenReturn(List.of());
        when(carryForwardRepository.save(any(CashAdvanceCarryForward.class))).thenAnswer(inv -> {
            CashAdvanceCarryForward row = inv.getArgument(0);
            if (row.getCarryForwardId() == null) {
                row.setCarryForwardId(UUID.randomUUID());
            }
            rows.put(row.getCarryForwardId(), row);
            return row;
        });
        when(balanceCalculator.outstanding(any(CashAdvance.class)))
                .thenAnswer(inv -> ((CashAdvance) inv.getArgument(0)).getOutstandingBalance());
        when(balanceLedger.applyBalance(any(), any(), any(), any(), any())).thenReturn(null);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ propose

    @Test
    void propose_byAFinanceExecutive_onADisbursedRequest_createsAProposalThatReferencesTheRollover() {
        signInAs(RoleConstants.ROLE_FINANCE_EXECUTIVE);
        when(currentUserService.getEmployeeId()).thenReturn(EXEC_B);

        var response = service.proposeCompensation(original.getCarryForwardId(), "Rollover was entered against the wrong advance");

        assertEquals(CashAdvanceCarryForward.PROPOSED, response.status());
        assertEquals(original.getCarryForwardId(), response.reversesCarryForwardId());
        assertEquals(CashAdvanceCarryForward.APPLIED, original.getStatus(), "the original is not changed by a proposal");
    }

    @Test
    void propose_isRefusedToAnyoneElse() {
        signInAs(RoleConstants.ROLE_FINANCE);
        assertThrows(AccessDeniedException.class,
                () -> service.proposeCompensation(original.getCarryForwardId(), "Wrong advance"));
    }

    @Test
    void propose_needsAReason() {
        signInAs(RoleConstants.ROLE_FINANCE_EXECUTIVE);
        when(currentUserService.getEmployeeId()).thenReturn(EXEC_B);
        assertThrows(BusinessRuleViolationException.class,
                () -> service.proposeCompensation(original.getCarryForwardId(), "  "));
    }

    @Test
    void propose_isRefusedWhileTheRequestIsNotDisbursed_becauseTheRolloverIsReversedDirectly() {
        signInAs(RoleConstants.ROLE_FINANCE_EXECUTIVE);
        when(currentUserService.getEmployeeId()).thenReturn(EXEC_B);
        destination.setDisbursedAt(null);
        assertThrows(BusinessRuleViolationException.class,
                () -> service.proposeCompensation(original.getCarryForwardId(), "Wrong advance"));
    }

    @Test
    void propose_isRefusedForAnAlreadyReversedRollover() {
        signInAs(RoleConstants.ROLE_FINANCE_EXECUTIVE);
        when(currentUserService.getEmployeeId()).thenReturn(EXEC_B);
        original.setStatus(CashAdvanceCarryForward.REVERSED);
        assertThrows(BusinessRuleViolationException.class,
                () -> service.proposeCompensation(original.getCarryForwardId(), "Wrong advance"));
    }

    @Test
    void propose_isRefusedWhileAnotherCompensationIsPending() {
        signInAs(RoleConstants.ROLE_FINANCE_EXECUTIVE);
        when(currentUserService.getEmployeeId()).thenReturn(EXEC_B);
        when(carryForwardRepository.findByReversesCarryForwardIdAndStatusIn(eq(original.getCarryForwardId()), anyCollection()))
                .thenReturn(List.of(CashAdvanceCarryForward.builder().status(CashAdvanceCarryForward.PROPOSED).build()));
        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class,
                () -> service.proposeCompensation(original.getCarryForwardId(), "Again"));
        assertTrue(ex.getMessage().contains("already pending"), ex.getMessage());
    }

    // ------------------------------------------------------------------ approve

    private CashAdvanceCarryForward proposeAs(String proposer) {
        signInAs(RoleConstants.ROLE_FINANCE_EXECUTIVE);
        when(currentUserService.getEmployeeId()).thenReturn(proposer);
        return rows.get(service.proposeCompensation(original.getCarryForwardId(), "Wrong advance").id());
    }

    @Test
    void approve_byASecondExecutive_reversesTheBalanceMovement_andKeepsTheCashAlreadyPaid() {
        CashAdvanceCarryForward compensation = proposeAs(EXEC_B);
        signInAs(RoleConstants.ROLE_FINANCE_EXECUTIVE);
        when(currentUserService.getEmployeeId()).thenReturn(EXEC_A);

        var response = service.approveCompensation(compensation.getCarryForwardId(), "Second approval");

        assertEquals(CashAdvanceCarryForward.APPLIED, response.status());
        assertEquals(CashAdvanceCarryForward.REVERSED, original.getStatus(), "the original rollover is kept, marked reversed");
        assertEquals(0, BigDecimal.ZERO.compareTo(source.getCarriedOutAmount()), "the source owes its balance again");
        assertEquals(0, new BigDecimal("4000").compareTo(source.getOutstandingBalance().max(source.getOutstandingBalance())),
                "the source's outstanding rose by the rolled amount");
        assertEquals(0, new BigDecimal("2000").compareTo(destination.getAmount()), "the destination's obligation fell by 4,000");
        assertEquals(0, BigDecimal.ZERO.compareTo(destination.getCarriedInAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(destination.getAuthorizedRolloverAmount()));
        assertEquals(0, new BigDecimal("2000").compareTo(destination.getDisbursedAmount()), "the cash already paid is unchanged");
        assertEquals(EXEC_A, compensation.getApprovedBy());
    }

    @Test
    void approve_byTheProposer_isRefused() {
        CashAdvanceCarryForward compensation = proposeAs(EXEC_B);
        signInAs(RoleConstants.ROLE_FINANCE_EXECUTIVE);
        when(currentUserService.getEmployeeId()).thenReturn(EXEC_B);
        assertThrows(BusinessRuleViolationException.class,
                () -> service.approveCompensation(compensation.getCarryForwardId(), "I approve my own"));
        assertEquals(CashAdvanceCarryForward.APPLIED, original.getStatus(), "nothing was reversed");
    }

    @Test
    void approve_isRefusedToANonExecutive() {
        CashAdvanceCarryForward compensation = proposeAs(EXEC_B);
        signInAs(RoleConstants.ROLE_FINANCE);
        when(currentUserService.getEmployeeId()).thenReturn(EXEC_A);
        assertThrows(AccessDeniedException.class,
                () -> service.approveCompensation(compensation.getCarryForwardId(), "Approve"));
    }

    @Test
    void approve_isRefusedIfTheCashAlreadyPaidWouldChange_andChangesNothing() {
        CashAdvanceCarryForward compensation = proposeAs(EXEC_B);
        destination.setDisbursedAmount(new BigDecimal("999")); // inconsistent with the amount less carried-in
        signInAs(RoleConstants.ROLE_FINANCE_EXECUTIVE);
        when(currentUserService.getEmployeeId()).thenReturn(EXEC_A);

        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class,
                () -> service.approveCompensation(compensation.getCarryForwardId(), "Approve"));
        assertTrue(ex.getMessage().contains("cash already paid"), ex.getMessage());
        assertEquals(CashAdvanceCarryForward.APPLIED, original.getStatus());
        assertEquals(0, new BigDecimal("6000").compareTo(destination.getAmount()));
    }

    @Test
    void approve_isRefusedIfTheOriginalRolloverHasAlreadyBeenReversed() {
        CashAdvanceCarryForward compensation = proposeAs(EXEC_B);
        original.setStatus(CashAdvanceCarryForward.REVERSED);
        signInAs(RoleConstants.ROLE_FINANCE_EXECUTIVE);
        when(currentUserService.getEmployeeId()).thenReturn(EXEC_A);
        assertThrows(BusinessRuleViolationException.class,
                () -> service.approveCompensation(compensation.getCarryForwardId(), "Approve"));
    }

    @Test
    void approve_ofAnAlreadyAppliedCompensation_isIdempotent() {
        CashAdvanceCarryForward compensation = proposeAs(EXEC_B);
        signInAs(RoleConstants.ROLE_FINANCE_EXECUTIVE);
        when(currentUserService.getEmployeeId()).thenReturn(EXEC_A);
        service.approveCompensation(compensation.getCarryForwardId(), "First");
        assertDoesNotThrow(() -> service.approveCompensation(compensation.getCarryForwardId(), "Retry"));
        verify(balanceLedger, times(2)).applyBalance(any(), any(), any(), any(), any());
    }
}
