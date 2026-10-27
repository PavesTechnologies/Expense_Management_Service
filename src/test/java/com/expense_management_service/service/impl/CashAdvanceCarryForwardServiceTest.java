package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.dto.request.CarryForwardDecisionRequest;
import com.expense_management_service.dto.request.CashAdvanceCarryForwardRequest;
import com.expense_management_service.dto.response.CashAdvanceCarryForwardResponse;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceCarryForward;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.CashAdvanceCarryForwardRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.security.CurrentUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CashAdvanceCarryForwardServiceTest {

    private static final String EMPLOYEE = "EMP001";
    private static final String FINANCE_APPROVER = "FIN002";
    private static final String GOOD_REASON = "Trip cancelled, balance to the next trip";

    @Mock
    private CashAdvanceRepository cashAdvanceRepository;

    @Mock
    private CashAdvanceCarryForwardRepository carryForwardRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private CurrentUserService currentUserService;

    private CashAdvanceCarryForwardService service;
    private UUID sourceId;
    private UUID destinationId;
    private CashAdvance source;
    private CashAdvance destination;
    private Currency inr;

    @BeforeEach
    void setUp() {
        service = new CashAdvanceCarryForwardService(cashAdvanceRepository, carryForwardRepository,
                new CashAdvanceBalanceLedger(auditLogRepository), currentUserService);
        ReflectionTestUtils.setField(service, "enabled", true);

        inr = Currency.builder().currencyCode("INR").decimalPlaces(2).build();
        sourceId = UUID.randomUUID();
        destinationId = UUID.randomUUID();
        source = CashAdvance.builder().advanceId(sourceId).employeeId(EMPLOYEE).currency(inr)
                .amount(new BigDecimal("1000.00")).outstandingBalance(new BigDecimal("1000.00"))
                .status("DISBURSED").build();
        destination = CashAdvance.builder().advanceId(destinationId).employeeId(EMPLOYEE).currency(inr)
                .amount(new BigDecimal("500.00")).status("APPROVED").build();

        lenient().when(cashAdvanceRepository.findById(sourceId)).thenReturn(Optional.of(source));
        lenient().when(cashAdvanceRepository.findById(destinationId)).thenReturn(Optional.of(destination));
        lenient().when(cashAdvanceRepository.findByIdForUpdate(sourceId)).thenReturn(Optional.of(source));
        lenient().when(cashAdvanceRepository.findByIdForUpdate(destinationId)).thenReturn(Optional.of(destination));
        lenient().when(carryForwardRepository.save(any(CashAdvanceCarryForward.class))).thenAnswer(inv -> {
            CashAdvanceCarryForward saved = inv.getArgument(0);
            if (saved.getCarryForwardId() == null) saved.setCarryForwardId(UUID.randomUUID());
            return saved;
        });
        lenient().when(carryForwardRepository.findBySourceAdvance_AdvanceIdAndStatusIn(any(), anyCollection()))
                .thenReturn(new ArrayList<>());
        lenient().when(carryForwardRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void actAs(String employeeId, String... roles) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(employeeId, null, authorities));
        lenient().when(currentUserService.getEmployeeId()).thenReturn(employeeId);
    }

    private CashAdvanceCarryForwardRequest proposal(BigDecimal amount) {
        return new CashAdvanceCarryForwardRequest(sourceId, destinationId, amount, GOOD_REASON, null);
    }

    private CashAdvanceCarryForward proposedAllocation(BigDecimal amount) {
        return CashAdvanceCarryForward.builder()
                .carryForwardId(UUID.randomUUID())
                .sourceAdvance(source).destinationAdvance(destination)
                .employeeId(EMPLOYEE).currencyCode("INR").amount(amount)
                .status(CashAdvanceCarryForward.PROPOSED).reason(GOOD_REASON)
                .requestedBy(EMPLOYEE).requestedAt(LocalDateTime.now())
                .build();
    }

    private CashAdvanceCarryForward appliedAllocation(BigDecimal amount) {
        CashAdvanceCarryForward allocation = proposedAllocation(amount);
        allocation.setStatus(CashAdvanceCarryForward.APPLIED);
        allocation.setApprovedBy(FINANCE_APPROVER);
        allocation.setAppliedAt(LocalDateTime.now());
        return allocation;
    }

    // ------------------------------------------------------------ feature switch

    @Test
    void whileDisabled_everyOperationIsRefused() {
        ReflectionTestUtils.setField(service, "enabled", false);
        actAs(EMPLOYEE, "GENERAL");

        assertThatThrownBy(() -> service.propose(proposal(new BigDecimal("100.00"))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("disabled until Finance confirms the review-cycle rules");
        verify(carryForwardRepository, never()).save(any());
    }

    // ------------------------------------------------------------ propose

    @Test
    void employeeProposesFromOwnSource_andTheProposalIsRecordedAsProposed() {
        actAs(EMPLOYEE, "GENERAL");

        CashAdvanceCarryForwardResponse response = service.propose(proposal(new BigDecimal("300.00")));

        assertThat(response.status()).isEqualTo("PROPOSED");
        assertThat(response.amount()).isEqualByComparingTo("300.00");
        assertThat(response.requestedBy()).isEqualTo(EMPLOYEE);
        assertThat(response.approvedBy()).isNull();
        assertThat(source.getOutstandingBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void partialAllocationsAndMultipleDestinations_areAllowedWithinTheVerifiedBalance() {
        actAs(EMPLOYEE, "GENERAL");
        UUID secondDestinationId = UUID.randomUUID();
        CashAdvance second = CashAdvance.builder().advanceId(secondDestinationId).employeeId(EMPLOYEE).currency(inr)
                .amount(new BigDecimal("500.00")).status("APPROVED").build();
        when(cashAdvanceRepository.findById(secondDestinationId)).thenReturn(Optional.of(second));
        when(carryForwardRepository.findBySourceAdvance_AdvanceIdAndStatusIn(eq(sourceId), anyCollection()))
                .thenReturn(List.of(proposedAllocation(new BigDecimal("300.00"))));

        CashAdvanceCarryForwardResponse response = service.propose(
                new CashAdvanceCarryForwardRequest(sourceId, secondDestinationId, new BigDecimal("700.00"), GOOD_REASON, null));

        assertThat(response.amount()).isEqualByComparingTo("700.00");
    }

    @Test
    void proposalAboveTheUnallocatedBalance_isRefused() {
        actAs(EMPLOYEE, "GENERAL");
        when(carryForwardRepository.findBySourceAdvance_AdvanceIdAndStatusIn(eq(sourceId), anyCollection()))
                .thenReturn(List.of(proposedAllocation(new BigDecimal("800.00"))));

        assertThatThrownBy(() -> service.propose(proposal(new BigDecimal("300.00"))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("exceeds the unallocated balance of 200.00");
    }

    @Test
    void anotherEmployeeCannotProposeFromSomeonesSource() {
        actAs("EMP999", "GENERAL");

        assertThatThrownBy(() -> service.propose(proposal(new BigDecimal("100.00"))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("owns the source advance or Finance");
    }

    @Test
    void financeMayProposeOnBehalfOfTheEmployee() {
        actAs(FINANCE_APPROVER, "FINANCE");

        assertThat(service.propose(proposal(new BigDecimal("100.00"))).status()).isEqualTo("PROPOSED");
    }

    @Test
    void overdueSource_isRefused_becauseCarryForwardCannotClearOverdue() {
        actAs(EMPLOYEE, "GENERAL");
        source.setStatus("OVERDUE");

        assertThatThrownBy(() -> service.propose(proposal(new BigDecimal("100.00"))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("overdue advance cannot be carried forward");
    }

    @Test
    void destinationThatIsNotApproved_isRefused() {
        actAs(EMPLOYEE, "GENERAL");
        destination.setStatus("REQUESTED");

        assertThatThrownBy(() -> service.propose(proposal(new BigDecimal("100.00"))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("approved and not yet disbursed");
    }

    @Test
    void destinationAlreadyDisbursed_isRefused() {
        actAs(EMPLOYEE, "GENERAL");
        destination.setDisbursedAt(LocalDateTime.now().minusDays(1));

        assertThatThrownBy(() -> service.propose(proposal(new BigDecimal("100.00"))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("not yet disbursed");
    }

    @Test
    void differentEmployee_isRefused() {
        actAs(EMPLOYEE, "GENERAL");
        destination.setEmployeeId("EMP777");

        assertThatThrownBy(() -> service.propose(proposal(new BigDecimal("100.00"))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("same employee");
    }

    @Test
    void differentCurrency_isRefused() {
        actAs(EMPLOYEE, "GENERAL");
        destination.setCurrency(Currency.builder().currencyCode("USD").decimalPlaces(2).build());

        assertThatThrownBy(() -> service.propose(proposal(new BigDecimal("100.00"))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("same currency");
    }

    @Test
    void sameAdvanceAsSourceAndDestination_isRefused() {
        actAs(EMPLOYEE, "GENERAL");

        assertThatThrownBy(() -> service.propose(
                new CashAdvanceCarryForwardRequest(sourceId, sourceId, new BigDecimal("100.00"), GOOD_REASON, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("two different advances");
    }

    @Test
    void reasonShorterThanTenCharacters_isRefused() {
        actAs(EMPLOYEE, "GENERAL");

        assertThatThrownBy(() -> service.propose(
                new CashAdvanceCarryForwardRequest(sourceId, destinationId, new BigDecimal("100.00"), "short", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 10 characters");
    }

    @Test
    void proposalThatWouldCloseACycle_isRefused() {
        actAs(EMPLOYEE, "GENERAL");
        // destination -> source already applied, so source -> destination would close a loop.
        CashAdvanceCarryForward existing = appliedAllocation(new BigDecimal("100.00"));
        existing.setSourceAdvance(destination);
        existing.setDestinationAdvance(source);
        when(carryForwardRepository.findBySourceAdvance_AdvanceIdAndStatusIn(eq(destinationId), anyCollection()))
                .thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.propose(proposal(new BigDecimal("100.00"))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("circular allocation");
    }

    @Test
    void replayWithTheSameIdempotencyKey_returnsTheOriginalWithoutANewRow() {
        actAs(EMPLOYEE, "GENERAL");
        CashAdvanceCarryForward earlier = proposedAllocation(new BigDecimal("300.00"));
        earlier.setIdempotencyKey("K-1");
        when(carryForwardRepository.findByIdempotencyKey("K-1")).thenReturn(Optional.of(earlier));

        CashAdvanceCarryForwardResponse response = service.propose(
                new CashAdvanceCarryForwardRequest(sourceId, destinationId, new BigDecimal("300.00"), GOOD_REASON, "K-1"));

        assertThat(response.carryForwardId()).isEqualTo(earlier.getCarryForwardId());
        verify(carryForwardRepository, never()).save(any());
    }

    // ------------------------------------------------------------ approve

    @Test
    void approvalByTheRequester_isRefused() {
        actAs(EMPLOYEE, "FINANCE");
        CashAdvanceCarryForward allocation = proposedAllocation(new BigDecimal("300.00"));
        when(carryForwardRepository.findByIdForUpdate(allocation.getCarryForwardId())).thenReturn(Optional.of(allocation));

        assertThatThrownBy(() -> service.approve(allocation.getCarryForwardId(), new CarryForwardDecisionRequest(GOOD_REASON)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("cannot approve their own carry-forward");
        assertThat(source.getOutstandingBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void approvalByNonFinance_isRefused() {
        actAs(EMPLOYEE, "GENERAL");
        CashAdvanceCarryForward allocation = proposedAllocation(new BigDecimal("300.00"));
        // The role check runs before the lookup, so this stub is only used if the check is wrong.
        lenient().when(carryForwardRepository.findByIdForUpdate(allocation.getCarryForwardId())).thenReturn(Optional.of(allocation));

        assertThatThrownBy(() -> service.approve(allocation.getCarryForwardId(), new CarryForwardDecisionRequest(GOOD_REASON)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only Finance");
    }

    @Test
    void approval_movesTheBalanceFromSourceToDestination_andRecordsTheAudit() {
        actAs(FINANCE_APPROVER, "FINANCE");
        CashAdvanceCarryForward allocation = proposedAllocation(new BigDecimal("300.00"));
        when(carryForwardRepository.findByIdForUpdate(allocation.getCarryForwardId())).thenReturn(Optional.of(allocation));
        when(carryForwardRepository.findBySourceAdvance_AdvanceIdAndStatusIn(eq(sourceId), anyCollection()))
                .thenReturn(List.of(allocation));

        CashAdvanceCarryForwardResponse response = service.approve(allocation.getCarryForwardId(),
                new CarryForwardDecisionRequest(GOOD_REASON));

        assertThat(response.status()).isEqualTo("APPLIED");
        assertThat(response.approvedBy()).isEqualTo(FINANCE_APPROVER);
        assertThat(source.getOutstandingBalance()).isEqualByComparingTo("700.00");
        assertThat(source.getStatus()).isEqualTo("SETTLEMENT_PENDING");
        assertThat(destination.getCarriedInAmount()).isEqualByComparingTo("300.00");
        verify(auditLogRepository, org.mockito.Mockito.atLeastOnce()).save(any());
    }

    @Test
    void approvalLocksTheAllocationFirst_andThenTheAdvancesInAscendingIdOrder() {
        actAs(FINANCE_APPROVER, "FINANCE");
        CashAdvanceCarryForward allocation = proposedAllocation(new BigDecimal("300.00"));
        when(carryForwardRepository.findByIdForUpdate(allocation.getCarryForwardId())).thenReturn(Optional.of(allocation));

        service.approve(allocation.getCarryForwardId(), new CarryForwardDecisionRequest(GOOD_REASON));

        UUID lower = sourceId.compareTo(destinationId) <= 0 ? sourceId : destinationId;
        UUID higher = lower.equals(sourceId) ? destinationId : sourceId;
        InOrder order = inOrder(carryForwardRepository, cashAdvanceRepository);
        order.verify(carryForwardRepository).findByIdForUpdate(allocation.getCarryForwardId());
        order.verify(cashAdvanceRepository).findByIdForUpdate(lower);
        order.verify(cashAdvanceRepository).findByIdForUpdate(higher);
    }

    @Test
    void approvalReplay_onAnAlreadyAppliedAllocation_movesNothingAgain() {
        actAs(FINANCE_APPROVER, "FINANCE");
        CashAdvanceCarryForward applied = appliedAllocation(new BigDecimal("300.00"));
        when(carryForwardRepository.findByIdForUpdate(applied.getCarryForwardId())).thenReturn(Optional.of(applied));

        CashAdvanceCarryForwardResponse response = service.approve(applied.getCarryForwardId(),
                new CarryForwardDecisionRequest(GOOD_REASON));

        assertThat(response.status()).isEqualTo("APPLIED");
        assertThat(source.getOutstandingBalance()).isEqualByComparingTo("1000.00");
        assertThat(destination.getCarriedInAmount()).isNull();
        verify(cashAdvanceRepository, never()).save(any());
    }

    @Test
    void approvalWhenActiveAllocationsNowExceedTheBalance_isRefused() {
        actAs(FINANCE_APPROVER, "FINANCE");
        CashAdvanceCarryForward allocation = proposedAllocation(new BigDecimal("300.00"));
        when(carryForwardRepository.findByIdForUpdate(allocation.getCarryForwardId())).thenReturn(Optional.of(allocation));
        when(carryForwardRepository.findBySourceAdvance_AdvanceIdAndStatusIn(eq(sourceId), anyCollection()))
                .thenReturn(List.of(allocation, proposedAllocation(new BigDecimal("900.00"))));

        assertThatThrownBy(() -> service.approve(allocation.getCarryForwardId(), new CarryForwardDecisionRequest(GOOD_REASON)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("exceed its verified outstanding balance");
        assertThat(source.getOutstandingBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void approvalAfterTheDestinationWasDisbursed_isRefused() {
        actAs(FINANCE_APPROVER, "FINANCE");
        CashAdvanceCarryForward allocation = proposedAllocation(new BigDecimal("300.00"));
        when(carryForwardRepository.findByIdForUpdate(allocation.getCarryForwardId())).thenReturn(Optional.of(allocation));
        destination.setDisbursedAt(LocalDateTime.now());

        assertThatThrownBy(() -> service.approve(allocation.getCarryForwardId(), new CarryForwardDecisionRequest(GOOD_REASON)))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(source.getOutstandingBalance()).isEqualByComparingTo("1000.00");
    }

    // ------------------------------------------------------------ reject

    @Test
    void rejectionOfAProposal_setsRejectedAndMovesNoBalance() {
        actAs(FINANCE_APPROVER, "FINANCE");
        CashAdvanceCarryForward allocation = proposedAllocation(new BigDecimal("300.00"));
        when(carryForwardRepository.findByIdForUpdate(allocation.getCarryForwardId())).thenReturn(Optional.of(allocation));

        CashAdvanceCarryForwardResponse response = service.reject(allocation.getCarryForwardId(),
                new CarryForwardDecisionRequest("Destination no longer needed"));

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(source.getOutstandingBalance()).isEqualByComparingTo("1000.00");
    }

    // ------------------------------------------------------------ reverse

    @Test
    void reversal_restoresBothBalances_andMarksTheAllocationReversed() {
        actAs(FINANCE_APPROVER, "FINANCE");
        source.setOutstandingBalance(new BigDecimal("700.00"));
        source.setStatus("SETTLEMENT_PENDING");
        destination.setCarriedInAmount(new BigDecimal("300.00"));
        CashAdvanceCarryForward applied = appliedAllocation(new BigDecimal("300.00"));
        when(carryForwardRepository.findByIdForUpdate(applied.getCarryForwardId())).thenReturn(Optional.of(applied));

        CashAdvanceCarryForwardResponse response = service.reverse(applied.getCarryForwardId(),
                new CarryForwardDecisionRequest("Trip went ahead after all"));

        assertThat(response.status()).isEqualTo("REVERSED");
        assertThat(source.getOutstandingBalance()).isEqualByComparingTo("1000.00");
        assertThat(destination.getCarriedInAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void reversal_isRefusedOnceTheDestinationIsDisbursed() {
        actAs(FINANCE_APPROVER, "FINANCE");
        source.setOutstandingBalance(new BigDecimal("700.00"));
        destination.setCarriedInAmount(new BigDecimal("300.00"));
        destination.setDisbursedAt(LocalDateTime.now());
        CashAdvanceCarryForward applied = appliedAllocation(new BigDecimal("300.00"));
        when(carryForwardRepository.findByIdForUpdate(applied.getCarryForwardId())).thenReturn(Optional.of(applied));

        assertThatThrownBy(() -> service.reverse(applied.getCarryForwardId(), new CarryForwardDecisionRequest("Reversal attempt")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("has been disbursed");
        assertThat(source.getOutstandingBalance()).isEqualByComparingTo("700.00");
    }

    @Test
    void reversal_thatWouldRestoreMoreThanTheAdvanceAmount_isRefused() {
        actAs(FINANCE_APPROVER, "FINANCE");
        source.setOutstandingBalance(new BigDecimal("1000.00"));
        destination.setCarriedInAmount(new BigDecimal("300.00"));
        CashAdvanceCarryForward applied = appliedAllocation(new BigDecimal("300.00"));
        when(carryForwardRepository.findByIdForUpdate(applied.getCarryForwardId())).thenReturn(Optional.of(applied));

        assertThatThrownBy(() -> service.reverse(applied.getCarryForwardId(), new CarryForwardDecisionRequest("Reversal attempt")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("more than the advance amount");
    }

    @Test
    void reversalOfAProposal_isRefused() {
        actAs(FINANCE_APPROVER, "FINANCE");
        CashAdvanceCarryForward allocation = proposedAllocation(new BigDecimal("300.00"));
        when(carryForwardRepository.findByIdForUpdate(allocation.getCarryForwardId())).thenReturn(Optional.of(allocation));

        assertThatThrownBy(() -> service.reverse(allocation.getCarryForwardId(), new CarryForwardDecisionRequest("Reversal attempt")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only applied carry-forwards can be reversed");
    }
}
