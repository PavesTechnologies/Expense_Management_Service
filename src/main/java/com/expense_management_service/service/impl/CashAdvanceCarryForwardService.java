package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.CarryForwardDecisionRequest;
import com.expense_management_service.dto.request.CashAdvanceCarryForwardRequest;
import com.expense_management_service.dto.response.CashAdvanceCarryForwardResponse;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceCarryForward;
import com.expense_management_service.repository.CashAdvanceCarryForwardRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import com.expense_management_service.security.RoleConstants;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import com.expense_management_service.dto.response.CarryForwardReadyResponse;

/**
 * Carry-forward: an unspent balance on a source advance is allocated to a destination advance.
 *
 * <p>Rules (decision record): a proposal comes from the employee who owns the source or from Finance, with a reason.
 * A Finance user other than the requester approves it. The combined active allocations from one source stay within
 * its verified outstanding balance. The destination must be APPROVED and not yet disbursed. A carry-forward never
 * clears an OVERDUE status or changes a deadline, so an overdue source is refused. Circular allocations are refused.
 *
 * <p>Approval: the source advance's manager or Finance (never the requester) approves or rejects a proposal. An
 * automatic proposal from a ready balance waits for that decision; no balance moves until it is approved.
 *
 * <p>Balance movement: approval reduces the source balance through {@link CashAdvanceBalanceLedger} and adds the
 * amount to the destination's {@code carriedInAmount}. The destination's obligation includes that amount when it is
 * disbursed. Reversal undoes both sides, with its own audit entries; no row is deleted.
 *
 * <p>The whole feature is off until {@code expense.carry-forward.enabled=true}, because the review-cycle boundaries
 * that gate cross-cycle allocations are not confirmed by Finance yet.
 */
@Service
@RequiredArgsConstructor
public class CashAdvanceCarryForwardService {

    static final int MIN_REASON_LENGTH = 10;

    /** The actor and reason recorded when a ready carry-forward is applied automatically. */
    static final String AUTO_ACTOR = "SYSTEM";
    static final String AUTO_REASON = "Applied automatically when the receiving advance was approved";

    /** Source statuses that can give up balance. OVERDUE is excluded so a carry-forward cannot clear it. */
    static final Set<String> SOURCE_ALLOWED_STATUSES = Set.of(
            "DISBURSED", "IN_PROGRESS", "SETTLEMENT_PENDING", "PARTIALLY_SETTLED",
            "PARTIALLY_ADJUSTED", "ADJUSTED", "EXPENSE_SUBMITTED", "EXPENSE_VERIFIED");

    private static final List<String> ACTIVE = List.of(CashAdvanceCarryForward.PROPOSED, CashAdvanceCarryForward.APPLIED);

    private final CashAdvanceRepository cashAdvanceRepository;
    private final CashAdvanceBalanceCalculator balanceCalculator;
    private final CashAdvanceCarryForwardRepository carryForwardRepository;
    private final CashAdvanceBalanceLedger balanceLedger;
    private final CurrentUserService currentUserService;

    @Value("${expense.carry-forward.enabled:false}")
    private boolean enabled = false;

    // ---------------------------------------------------------------- propose

    @Transactional
    public CashAdvanceCarryForwardResponse propose(CashAdvanceCarryForwardRequest request) {
        requireEnabled();
        String actor = currentUserService.getEmployeeId();
        String reason = requireReason(request.reason());
        BigDecimal amount = requirePositive(request.amount());

        String key = blankToNull(request.idempotencyKey());
        if (key != null) {
            Optional<CashAdvanceCarryForward> earlier = carryForwardRepository.findByIdempotencyKey(key);
            if (earlier.isPresent()) {
                CashAdvanceCarryForward prior = earlier.get();
                if (!prior.getSourceAdvance().getAdvanceId().equals(request.sourceAdvanceId())
                        || !prior.getDestinationAdvance().getAdvanceId().equals(request.destinationAdvanceId())
                        || prior.getAmount().compareTo(amount) != 0) {
                    throw new BusinessRuleViolationException("This idempotency key was already used for a different carry-forward.");
                }
                return toResponse(prior);
            }
        }

        if (request.sourceAdvanceId().equals(request.destinationAdvanceId())) {
            throw new BusinessRuleViolationException("A carry-forward needs two different advances.");
        }
        CashAdvance source = findAdvance(request.sourceAdvanceId());
        CashAdvance destination = findAdvance(request.destinationAdvanceId());

        if (!isFinanceCaller() && !sameEmployee(actor, source.getEmployeeId())) {
            throw new BusinessRuleViolationException("Only the employee who owns the source advance or Finance can propose a carry-forward.");
        }
        assertSameEmployeeAndCurrency(source, destination);
        assertSourceCanGiveBalance(source);
        assertDestinationEligible(destination);
        assertNoCycle(source.getAdvanceId(), destination.getAdvanceId());

        BigDecimal outstanding = outstanding(source);
        BigDecimal alreadyAllocated = allocatedFrom(source.getAdvanceId());
        if (alreadyAllocated.add(amount).compareTo(outstanding) > 0) {
            throw new BusinessRuleViolationException("Carry-forward of " + amount + " exceeds the unallocated balance of "
                    + outstanding.subtract(alreadyAllocated) + " on this advance.");
        }

        CashAdvanceCarryForward allocation = CashAdvanceCarryForward.builder()
                .sourceAdvance(source)
                .destinationAdvance(destination)
                .employeeId(source.getEmployeeId())
                .currencyCode(currencyCode(source))
                .amount(amount)
                .status(CashAdvanceCarryForward.PROPOSED)
                .reason(reason)
                .idempotencyKey(key)
                .requestedBy(actor)
                .requestedAt(LocalDateTime.now())
                .build();
        CashAdvanceCarryForward saved = carryForwardRepository.save(allocation);
        balanceLedger.recordChange("CashAdvanceCarryForward", saved.getCarryForwardId(), "CARRY_FORWARD_PROPOSED",
                null, "Status: PROPOSED, Amount: " + amount, actor, reason);
        return toResponse(saved);
    }

    // ---------------------------------------------------------------- approve

    @Transactional
    public CashAdvanceCarryForwardResponse approve(UUID carryForwardId, CarryForwardDecisionRequest decision) {
        requireEnabled();
        String approver = currentUserService.getEmployeeId();
        String reason = requireReason(decision.reason());

        CashAdvanceCarryForward allocation = lockAllocation(carryForwardId);
        if (CashAdvanceCarryForward.APPLIED.equals(allocation.getStatus())) {
            return toResponse(allocation);
        }
        requireStatus(allocation, CashAdvanceCarryForward.PROPOSED, "approved");
        requireApprover(allocation);

        if (approver == null || approver.equals(allocation.getRequestedBy())) {
            throw new BusinessRuleViolationException("The requester cannot approve their own carry-forward.");
        }

        CashAdvance[] advances = lockAdvancesInOrder(allocation);
        CashAdvance source = advances[0];
        CashAdvance destination = advances[1];
        assertSourceCanGiveBalance(source);
        assertDestinationEligible(destination);
        assertNoCycle(source.getAdvanceId(), destination.getAdvanceId());

        // Capacity is checked again under the lock, including this proposal's own amount.
        BigDecimal outstanding = outstanding(source);
        if (allocatedFrom(source.getAdvanceId()).compareTo(outstanding) > 0) {
            throw new BusinessRuleViolationException("Active carry-forwards from this advance exceed its verified outstanding balance of "
                    + outstanding + ".");
        }

        applyBalances(source, destination, allocation.getAmount(), approver, reason);
        // The approved part is no longer waiting to be carried forward.
        if (source.getCarryForwardReadyAmount() != null) {
            BigDecimal left = source.getCarryForwardReadyAmount().subtract(allocation.getAmount());
            source.setCarryForwardReadyAmount(left.signum() > 0 ? left : null);
            cashAdvanceRepository.save(source);
        }

        LocalDateTime now = LocalDateTime.now();
        allocation.setStatus(CashAdvanceCarryForward.APPLIED);
        allocation.setApprovedBy(approver);
        allocation.setApprovedAt(now);
        allocation.setAppliedAt(now);
        CashAdvanceCarryForward saved = carryForwardRepository.save(allocation);
        balanceLedger.recordChange("CashAdvanceCarryForward", saved.getCarryForwardId(), "CARRY_FORWARD_APPLIED",
                "Status: PROPOSED", "Status: APPLIED, Amount: " + saved.getAmount(), approver, reason);
        return toResponse(saved);
    }

    // ---------------------------------------------------------------- ready (applied when the next advance is approved)

    /**
     * Marks the unused balance of a source advance as ready to carry forward. It is applied automatically to the
     * employee's next INR advance when that advance is approved. Replaces any amount already ready on this source.
     */
    @Transactional
    public CarryForwardReadyResponse markReady(UUID sourceAdvanceId, BigDecimal amount, String reason) {
        requireEnabled();
        String actor = currentUserService.getEmployeeId();
        String why = requireReason(reason);
        BigDecimal requested = requirePositive(amount);
        CashAdvance source = lockAdvance(sourceAdvanceId);
        if (!isFinanceCaller() && !sameEmployee(actor, source.getEmployeeId())) {
            throw new BusinessRuleViolationException("Only the employee who owns the advance or Finance can mark a carry-forward ready.");
        }
        assertSourceCanGiveBalance(source);
        BigDecimal available = outstanding(source).subtract(allocatedFrom(source.getAdvanceId()));
        if (requested.compareTo(available) > 0) {
            throw new BusinessRuleViolationException("Carry-forward of " + requested + " exceeds the unallocated balance of "
                    + available + " on this advance.");
        }
        source.setCarryForwardReadyAmount(requested);
        cashAdvanceRepository.save(source);
        balanceLedger.recordChange("CashAdvance", source.getAdvanceId(), "CARRY_FORWARD_READY", null,
                "Ready: " + requested, actor, why);
        return readyResponse(source);
    }

    /** The carry-forward ready on this advance, or a response with a null amount when none is ready. */
    @Transactional(readOnly = true)
    public CarryForwardReadyResponse readyFor(UUID advanceId) {
        return readyResponse(findAdvance(advanceId));
    }

    /** Withdraws a ready carry-forward before it has been applied. */
    @Transactional
    public CarryForwardReadyResponse cancelReady(UUID sourceAdvanceId, String reason) {
        requireEnabled();
        String actor = currentUserService.getEmployeeId();
        String why = requireReason(reason);
        CashAdvance source = lockAdvance(sourceAdvanceId);
        if (!isFinanceCaller() && !sameEmployee(actor, source.getEmployeeId())) {
            throw new BusinessRuleViolationException("Only the employee who owns the advance or Finance can cancel a ready carry-forward.");
        }
        BigDecimal previous = source.getCarryForwardReadyAmount();
        if (previous != null) {
            source.setCarryForwardReadyAmount(null);
            cashAdvanceRepository.save(source);
            balanceLedger.recordChange("CashAdvance", source.getAdvanceId(), "CARRY_FORWARD_READY_CANCELLED",
                    "Ready: " + previous, null, actor, why);
        }
        return readyResponse(source);
    }

    /**
     * Proposes the carry-forwards that are ready for this employee and currency against a destination advance that has
     * just been approved, oldest ready source first. Each proposal is capped at the source's unallocated balance and at
     * what the destination can still carry, counting proposals already waiting on it. Nothing moves until the source
     * advance's manager or Finance approves the proposal. Does nothing while the feature is disabled.
     */
    public void applyReadyForDestination(CashAdvance destination) {
        if (!enabled || destination.getEmployeeId() == null) {
            return;
        }
        String currency = currencyCode(destination);
        for (CashAdvance source : cashAdvanceRepository
                .findByEmployeeIdAndCarryForwardReadyAmountIsNotNullOrderByCreatedAtAsc(destination.getEmployeeId())) {
            if (source.getAdvanceId().equals(destination.getAdvanceId()) || !currency.equals(currencyCode(source))) {
                continue;
            }
            BigDecimal readyAmount = source.getCarryForwardReadyAmount();
            if (proposalExists(source.getAdvanceId(), destination.getAdvanceId())) {
                continue;
            }
            BigDecimal canCarry = destination.getAmount()
                    .subtract(CashAdvanceBalanceRules.carriedIn(destination))
                    .subtract(pendingInto(destination.getAdvanceId()));
            BigDecimal unallocated = outstanding(source).subtract(allocatedFrom(source.getAdvanceId()));
            BigDecimal amount = readyAmount.min(unallocated).min(canCarry);
            if (amount.signum() <= 0 || !canApply(source, destination)) {
                continue;
            }
            LocalDateTime now = LocalDateTime.now();
            CashAdvanceCarryForward row = carryForwardRepository.save(CashAdvanceCarryForward.builder()
                    .sourceAdvance(source)
                    .destinationAdvance(destination)
                    .employeeId(source.getEmployeeId())
                    .currencyCode(currency)
                    .amount(amount)
                    .status(CashAdvanceCarryForward.PROPOSED)
                    .reason(AUTO_REASON)
                    .requestedBy(AUTO_ACTOR)
                    .requestedAt(now)
                    .build());
            balanceLedger.recordChange("CashAdvanceCarryForward", row.getCarryForwardId(), "CARRY_FORWARD_PROPOSED",
                    null, "Status: PROPOSED, Amount: " + amount, AUTO_ACTOR, AUTO_REASON);
        }
    }

    /**
     * Refuses disbursement while a carry-forward proposal into this advance is still waiting for its manager or Finance
     * decision. Paying the full cash before that decision would pay the carried-in part twice.
     */
    @Transactional(readOnly = true)
    public void assertNoPendingProposalInto(UUID destinationAdvanceId) {
        if (pendingInto(destinationAdvanceId).signum() > 0) {
            throw new BusinessRuleViolationException("A carry-forward into this advance is waiting for approval by the "
                    + "employee's manager or Finance. Resolve it before disbursement.");
        }
    }

    private boolean proposalExists(UUID sourceId, UUID destinationId) {
        return carryForwardRepository.findBySourceAdvance_AdvanceIdAndStatusIn(sourceId, List.of(CashAdvanceCarryForward.PROPOSED))
                .stream().anyMatch(cf -> cf.getDestinationAdvance().getAdvanceId().equals(destinationId));
    }

    /** Sum of proposals into this advance that are still waiting for a decision. */
    private BigDecimal pendingInto(UUID destinationId) {
        return sum(carryForwardRepository.findByDestinationAdvance_AdvanceIdAndStatusIn(
                destinationId, List.of(CashAdvanceCarryForward.PROPOSED)));
    }

    private boolean canApply(CashAdvance source, CashAdvance destination) {
        try {
            assertSourceCanGiveBalance(source);
            assertNoCycle(source.getAdvanceId(), destination.getAdvanceId());
            return true;
        } catch (BusinessRuleViolationException e) {
            return false;
        }
    }

    private CarryForwardReadyResponse readyResponse(CashAdvance advance) {
        return new CarryForwardReadyResponse(advance.getAdvanceId(), currencyCode(advance),
                advance.getCarryForwardReadyAmount(), outstanding(advance));
    }

    // ---------------------------------------------------------------- reject

    @Transactional
    public CashAdvanceCarryForwardResponse reject(UUID carryForwardId, CarryForwardDecisionRequest decision) {
        requireEnabled();
        String actor = currentUserService.getEmployeeId();
        String reason = requireReason(decision.reason());

        CashAdvanceCarryForward allocation = lockAllocation(carryForwardId);
        requireStatus(allocation, CashAdvanceCarryForward.PROPOSED, "rejected");
        requireApprover(allocation);

        allocation.setStatus(CashAdvanceCarryForward.REJECTED);
        allocation.setRejectedBy(actor);
        allocation.setRejectedAt(LocalDateTime.now());
        allocation.setDecisionReason(reason);
        CashAdvanceCarryForward saved = carryForwardRepository.save(allocation);
        balanceLedger.recordChange("CashAdvanceCarryForward", saved.getCarryForwardId(), "CARRY_FORWARD_REJECTED",
                "Status: PROPOSED", "Status: REJECTED", actor, reason);
        return toResponse(saved);
    }

    // ---------------------------------------------------------------- reverse

    @Transactional
    public CashAdvanceCarryForwardResponse reverse(UUID carryForwardId, CarryForwardDecisionRequest decision) {
        requireEnabled();
        requireFinance();
        String actor = currentUserService.getEmployeeId();
        String reason = requireReason(decision.reason());

        CashAdvanceCarryForward allocation = lockAllocation(carryForwardId);
        if (CashAdvanceCarryForward.REVERSED.equals(allocation.getStatus())) {
            return toResponse(allocation);
        }
        requireStatus(allocation, CashAdvanceCarryForward.APPLIED, "reversed");

        CashAdvance[] advances = lockAdvancesInOrder(allocation);
        CashAdvance source = advances[0];
        CashAdvance destination = advances[1];
        if (destination.getDisbursedAt() != null) {
            throw new BusinessRuleViolationException("The destination advance has been disbursed, so this carry-forward cannot be reversed.");
        }
        BigDecimal newCarried = CashAdvanceBalanceRules.carriedIn(destination).subtract(allocation.getAmount());
        if (newCarried.signum() < 0) {
            throw new BusinessRuleViolationException("Reversing this carry-forward would leave a negative carried-in balance.");
        }

        int decimals = source.getCurrency() != null ? source.getCurrency().getDecimalPlaces() : 2;
        BigDecimal restored = outstanding(source).add(allocation.getAmount());
        if (restored.compareTo(source.getAmount()) > 0) {
            throw new BusinessRuleViolationException("Reversing this carry-forward would restore more than the advance amount.");
        }
        balanceLedger.applyBalance(source, CashAdvanceBalanceRules.requireNonNegative(restored, decimals),
                "CASH_ADVANCE_CARRY_FORWARD_REVERSED", actor, reason);
        source.setCarriedOutAmount(CashAdvanceBalanceRules.carriedOut(source).subtract(allocation.getAmount()));
        cashAdvanceRepository.save(source);

        BigDecimal oldCarried = CashAdvanceBalanceRules.carriedIn(destination);
        destination.setCarriedInAmount(newCarried);
        cashAdvanceRepository.save(destination);
        balanceLedger.recordChange("CashAdvance", destination.getAdvanceId(), "CASH_ADVANCE_CARRIED_FORWARD_REVERSED",
                "CarriedIn: " + oldCarried, "CarriedIn: " + newCarried, actor, reason);

        allocation.setStatus(CashAdvanceCarryForward.REVERSED);
        allocation.setReversedBy(actor);
        allocation.setReversedAt(LocalDateTime.now());
        allocation.setReversalReason(reason);
        CashAdvanceCarryForward saved = carryForwardRepository.save(allocation);
        balanceLedger.recordChange("CashAdvanceCarryForward", saved.getCarryForwardId(), "CARRY_FORWARD_REVERSED",
                "Status: APPLIED", "Status: REVERSED", actor, reason);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public CashAdvanceCarryForwardResponse get(UUID carryForwardId) {
        return carryForwardRepository.findById(carryForwardId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Carry-forward not found: " + carryForwardId));
    }

    /**
     * Moves {@code amount} from the source's balance to the destination's carried-in balance. The destination's carried-in
     * balance cannot exceed its amount, because the carried-in part is what the employee already holds.
     */
    private void applyBalances(CashAdvance source, CashAdvance destination, BigDecimal amount, String actor, String reason) {
        if (CashAdvanceBalanceRules.carriedIn(destination).add(amount).compareTo(destination.getAmount()) > 0) {
            throw new BusinessRuleViolationException("Carried-in balance would exceed the receiving advance's amount of "
                    + destination.getAmount() + ".");
        }
        int decimals = source.getCurrency() != null ? source.getCurrency().getDecimalPlaces() : 2;
        BigDecimal newSourceBalance = CashAdvanceBalanceRules.requireNonNegative(outstanding(source).subtract(amount), decimals);
        balanceLedger.applyBalance(source, newSourceBalance, "CASH_ADVANCE_CARRIED_FORWARD_OUT", actor, reason);
        source.setCarriedOutAmount(CashAdvanceBalanceRules.carriedOut(source).add(amount));
        cashAdvanceRepository.save(source);

        BigDecimal oldCarried = CashAdvanceBalanceRules.carriedIn(destination);
        BigDecimal newCarried = oldCarried.add(amount);
        destination.setCarriedInAmount(newCarried);
        cashAdvanceRepository.save(destination);
        balanceLedger.recordChange("CashAdvance", destination.getAdvanceId(), "CASH_ADVANCE_CARRIED_FORWARD_IN",
                "CarriedIn: " + oldCarried, "CarriedIn: " + newCarried, actor, reason);
    }


    // ---------------------------------------------------------------- rollover (Cash Advance + Outstanding)

    /** An overdue advance Finance has chosen to roll from, with the amount and the reason. Only a FINANCE_EXECUTIVE may give one. */
    public record OverdueException(UUID sourceAdvanceId, BigDecimal amount, String justification) {
    }

    /** One source's share of a rollover. */
    private record RolloverTake(CashAdvance source, BigDecimal amount, boolean overdue) {
    }

    /** Statuses a rollover may draw from: the eligible ones, and OVERDUE (only with an explicit exception). */
    static final List<String> ROLLOVER_LOOKUP_STATUSES = List.of(
            "DISBURSED", "IN_PROGRESS", "SETTLEMENT_PENDING", "PARTIALLY_SETTLED", "PARTIALLY_ADJUSTED", "ADJUSTED", "OVERDUE");

    /**
     * Rolls the authorised amount into a destination request, oldest eligible advance first, and records one rollover row per
     * source. Runs inside the caller's transaction, so the rows, the balance movements and the approval commit together or
     * not at all. The destination's amount becomes cashAmount + rollover: the rolled-in balance is part of what the request
     * covers, and disbursement pays only the cash part (amount minus carried-in). Returns the total rolled in.
     *
     * <p>Sources are locked, and their outstanding balance recalculated, under the lock. The destination must already be
     * locked by the caller. Requires the carry-forward feature, which is off until its review-cycle rules are confirmed.
     */
    @Transactional
    public BigDecimal applyRollover(CashAdvance destination, BigDecimal rolloverAmount, BigDecimal cashAmount,
                                    OverdueException overdue, String approver, String reason, boolean financeExecutive) {
        requireEnabled();
        if (approver == null || approver.isBlank()) {
            throw new BusinessRuleViolationException("A Finance approver is required to authorise a rollover.");
        }
        if (rolloverAmount == null || rolloverAmount.signum() <= 0) {
            throw new BusinessRuleViolationException("Authorise a rollover amount greater than zero.");
        }
        if (cashAmount == null || cashAmount.signum() < 0) {
            throw new BusinessRuleViolationException("The cash amount cannot be negative.");
        }
        if (destination.getDisbursedAt() != null) {
            throw new BusinessRuleViolationException("The request has already been disbursed, so it cannot take a rollover.");
        }
        if (!carryForwardRepository.findByDestinationAdvance_AdvanceIdAndStatusIn(destination.getAdvanceId(), ACTIVE).isEmpty()) {
            throw new BusinessRuleViolationException("This request already has a rollover. It cannot be applied twice.");
        }
        if (overdue != null) {
            if (!financeExecutive) {
                throw new BusinessRuleViolationException("Only a Finance executive can authorise a rollover from an overdue advance.");
            }
            if (overdue.justification() == null || overdue.justification().isBlank()) {
                throw new BusinessRuleViolationException("Give the reason for rolling over an overdue advance.");
            }
            if (overdue.amount() == null || overdue.amount().signum() <= 0) {
                throw new BusinessRuleViolationException("Authorise the amount to roll over from the overdue advance.");
            }
        }

        String employeeId = destination.getEmployeeId();
        String currency = currencyCode(destination);
        List<CashAdvance> candidates = new java.util.ArrayList<>();
        for (CashAdvance advance : cashAdvanceRepository.findByEmployeeIdAndStatusIn(employeeId, ROLLOVER_LOOKUP_STATUSES)) {
            if (advance.getAdvanceId().equals(destination.getAdvanceId()) || !currency.equals(currencyCode(advance))) {
                continue;
            }
            boolean overdueSource = overdue != null && overdue.sourceAdvanceId().equals(advance.getAdvanceId());
            boolean overdueStatus = "OVERDUE".equalsIgnoreCase(advance.getStatus());
            if (overdueStatus ? !overdueSource : !CashAdvanceAllocationRules.rolloverEligible(advance.getStatus())) {
                continue;
            }
            candidates.add(advance);
        }
        if (overdue != null && candidates.stream().noneMatch(a -> a.getAdvanceId().equals(overdue.sourceAdvanceId()))) {
            throw new BusinessRuleViolationException("The overdue advance is not an eligible source for this employee.");
        }

        // Lock the sources in id order, so concurrent rollovers and carry-forwards queue instead of deadlocking.
        candidates.sort(java.util.Comparator.comparing(CashAdvance::getAdvanceId));
        for (CashAdvance advance : candidates) {
            lockAdvance(advance.getAdvanceId());
        }

        // First in, first out: the oldest advance gives its balance first.
        candidates.sort(java.util.Comparator
                .comparing(CashAdvance::getCreatedAt, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                .thenComparing(CashAdvance::getAdvanceId));
        List<RolloverTake> plan = new java.util.ArrayList<>();
        BigDecimal remaining = rolloverAmount;
        for (CashAdvance source : candidates) {
            if (remaining.signum() <= 0) {
                break;
            }
            boolean overdueSource = overdue != null && overdue.sourceAdvanceId().equals(source.getAdvanceId());
            BigDecimal free = outstanding(source).subtract(allocatedFrom(source.getAdvanceId()));
            if (overdueSource) {
                free = free.min(overdue.amount());
            }
            if (free.signum() <= 0) {
                continue;
            }
            BigDecimal take = free.min(remaining);
            plan.add(new RolloverTake(source, take, overdueSource));
            remaining = remaining.subtract(take);
        }
        if (remaining.signum() > 0) {
            throw new BusinessRuleViolationException("Only " + rolloverAmount.subtract(remaining).toPlainString()
                    + " of the authorised " + rolloverAmount.toPlainString() + " is available from the eligible advances.");
        }
        if (overdue != null && plan.stream().noneMatch(RolloverTake::overdue)) {
            throw new BusinessRuleViolationException("The overdue advance is not needed for this rollover. Remove the overdue authorisation.");
        }

        destination.setAmount(cashAmount.add(rolloverAmount));
        destination.setAuthorizedRolloverAmount(rolloverAmount);
        cashAdvanceRepository.save(destination);

        LocalDateTime now = LocalDateTime.now();
        for (RolloverTake take : plan) {
            carryForwardRepository.save(CashAdvanceCarryForward.builder()
                    .sourceAdvance(take.source())
                    .destinationAdvance(destination)
                    .employeeId(employeeId)
                    .currencyCode(currency)
                    .amount(take.amount())
                    .status(CashAdvanceCarryForward.APPLIED)
                    .reason(reason)
                    .idempotencyKey("rollover:" + destination.getAdvanceId() + ":" + take.source().getAdvanceId())
                    .requestedBy(employeeId)
                    .requestedAt(now)
                    .approvedBy(approver)
                    .approvedAt(now)
                    .appliedAt(now)
                    .authorizationType(take.overdue() ? "OVERDUE_EXCEPTION" : "STANDARD")
                    .exceptionJustification(take.overdue() ? overdue.justification().trim() : null)
                    .exceptionAuthorizedBy(take.overdue() ? approver : null)
                    .exceptionAuthorizedAt(take.overdue() ? now : null)
                    .build());
            applyBalances(take.source(), destination, take.amount(), approver, reason);
        }
        return rolloverAmount;
    }


    // ---------------------------------------------------------------- compensating reversal after disbursement

    /**
     * Proposes a compensating reversal of an applied rollover whose request has already been disbursed. The original row is
     * not changed until a second Finance executive approves the compensation. Needs no feature flag: rollovers that already
     * exist must still be correctable if the feature is later switched off.
     */
    @Transactional
    public CashAdvanceCarryForwardResponse proposeCompensation(UUID originalId, String reason) {
        requireExecutive();
        String actor = currentUserService.getEmployeeId();
        String text = requireReason(reason);
        CashAdvanceCarryForward original = lockAllocation(originalId);
        if (!CashAdvanceCarryForward.APPLIED.equals(original.getStatus())) {
            throw new BusinessRuleViolationException("Only an applied rollover can be compensated. Current status: " + original.getStatus());
        }
        CashAdvance destination = lockAdvance(original.getDestinationAdvance().getAdvanceId());
        if (destination.getDisbursedAt() == null) {
            throw new BusinessRuleViolationException("The request is not disbursed yet, so the rollover can be reversed directly instead.");
        }
        if (!carryForwardRepository.findByReversesCarryForwardIdAndStatusIn(originalId,
                List.of(CashAdvanceCarryForward.PROPOSED, CashAdvanceCarryForward.APPLIED)).isEmpty()) {
            throw new BusinessRuleViolationException("A compensation is already pending or applied for this rollover.");
        }
        LocalDateTime now = LocalDateTime.now();
        CashAdvanceCarryForward compensation = carryForwardRepository.save(CashAdvanceCarryForward.builder()
                .sourceAdvance(original.getSourceAdvance())
                .destinationAdvance(destination)
                .employeeId(original.getEmployeeId())
                .currencyCode(original.getCurrencyCode())
                .amount(original.getAmount())
                .status(CashAdvanceCarryForward.PROPOSED)
                .reason(text)
                .idempotencyKey("compensation:" + originalId)
                .requestedBy(actor)
                .requestedAt(now)
                .reversesCarryForwardId(originalId)
                .build());
        return toResponse(compensation);
    }

    /**
     * Approves a compensation proposed by another Finance executive. Moves the rolled balance back to the source advance and
     * takes it off the destination's obligation. The cash already paid on the destination is unchanged, and the original
     * rollover is marked REVERSED with the compensation as its reference.
     */
    @Transactional
    public CashAdvanceCarryForwardResponse approveCompensation(UUID compensationId, String reason) {
        requireExecutive();
        String approver = currentUserService.getEmployeeId();
        String text = requireReason(reason);
        CashAdvanceCarryForward compensation = lockAllocation(compensationId);
        if (CashAdvanceCarryForward.APPLIED.equals(compensation.getStatus())) {
            return toResponse(compensation);
        }
        requireStatus(compensation, CashAdvanceCarryForward.PROPOSED, "approved");
        if (compensation.getRequestedBy() == null || approver == null || approver.equals(compensation.getRequestedBy())) {
            throw new BusinessRuleViolationException("The person who proposed the compensation cannot approve it.");
        }
        CashAdvanceCarryForward original = lockAllocation(compensation.getReversesCarryForwardId());
        if (!CashAdvanceCarryForward.APPLIED.equals(original.getStatus())) {
            throw new BusinessRuleViolationException("The original rollover is no longer applied. Current status: " + original.getStatus());
        }
        CashAdvance[] advances = lockAdvancesInOrder(compensation);
        CashAdvance source = advances[0];
        CashAdvance destination = advances[1];
        if (destination.getDisbursedAt() == null) {
            throw new BusinessRuleViolationException("The request is not disbursed, so the rollover is reversed directly instead.");
        }
        reverseBalances(source, destination, compensation.getAmount(), approver, text);

        LocalDateTime now = LocalDateTime.now();
        original.setStatus(CashAdvanceCarryForward.REVERSED);
        carryForwardRepository.save(original);
        compensation.setStatus(CashAdvanceCarryForward.APPLIED);
        compensation.setApprovedBy(approver);
        compensation.setApprovedAt(now);
        compensation.setAppliedAt(now);
        CashAdvanceCarryForward saved = carryForwardRepository.save(compensation);
        balanceLedger.recordChange("CashAdvanceCarryForward", saved.getCarryForwardId(), "CARRY_FORWARD_COMPENSATED",
                "Status: PROPOSED", "Status: APPLIED, reverses " + original.getCarryForwardId(), approver, text);
        return toResponse(saved);
    }

    /** Reverses a rollover's balance movement. The destination's cash is unchanged: only its obligation drops. */
    private void reverseBalances(CashAdvance source, CashAdvance destination, BigDecimal amount, String actor, String reason) {
        BigDecimal newCarried = CashAdvanceBalanceRules.carriedIn(destination).subtract(amount);
        BigDecimal newAmount = destination.getAmount().subtract(amount);
        if (newCarried.signum() < 0 || newAmount.signum() < 0) {
            throw new BusinessRuleViolationException("The compensation would take more than the request carries.");
        }
        if (destination.getDisbursedAmount() != null
                && newAmount.subtract(newCarried).compareTo(destination.getDisbursedAmount()) != 0) {
            throw new BusinessRuleViolationException("The compensation would change the cash already paid on this request. Nothing was changed.");
        }
        int decimals = source.getCurrency() != null ? source.getCurrency().getDecimalPlaces() : 2;
        BigDecimal newSourceBalance = CashAdvanceBalanceRules.requireNonNegative(outstanding(source).add(amount), decimals);
        balanceLedger.applyBalance(source, newSourceBalance, "CASH_ADVANCE_CARRIED_FORWARD_COMPENSATED_OUT", actor, reason);
        source.setCarriedOutAmount(CashAdvanceBalanceRules.carriedOut(source).subtract(amount));
        cashAdvanceRepository.save(source);

        BigDecimal oldAmount = destination.getAmount();
        BigDecimal oldCarried = CashAdvanceBalanceRules.carriedIn(destination);
        destination.setAmount(newAmount);
        destination.setCarriedInAmount(newCarried);
        if (destination.getAuthorizedRolloverAmount() != null) {
            destination.setAuthorizedRolloverAmount(destination.getAuthorizedRolloverAmount().subtract(amount).max(BigDecimal.ZERO));
        }
        cashAdvanceRepository.save(destination);
        balanceLedger.recordChange("CashAdvance", destination.getAdvanceId(), "CASH_ADVANCE_CARRIED_FORWARD_COMPENSATED_IN",
                "Amount: " + oldAmount + ", CarriedIn: " + oldCarried, "Amount: " + newAmount + ", CarriedIn: " + newCarried,
                actor, reason);
    }

    /** Only a Finance executive may propose or approve a compensation. Checked here, not only at the endpoint. */
    private static void requireExecutive() {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        boolean executive = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> RoleConstants.ROLE_FINANCE_EXECUTIVE.equals(a.getAuthority()));
        if (!executive) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Only a Finance executive can propose or approve a compensating reversal.");
        }
    }


    // ---------------------------------------------------------------- rules

    /** Locks the allocation row before any advance, so every operation takes locks in the same order. */
    private CashAdvanceCarryForward lockAllocation(UUID id) {
        return carryForwardRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Carry-forward not found: " + id));
    }

    /**
     * Locks the source and destination advances in ascending id order, so two carry-forwards that touch the same
     * pair of advances in opposite directions cannot deadlock. Returns {source, destination} in that order.
     */
    private CashAdvance[] lockAdvancesInOrder(CashAdvanceCarryForward allocation) {
        UUID sourceId = allocation.getSourceAdvance().getAdvanceId();
        UUID destinationId = allocation.getDestinationAdvance().getAdvanceId();
        boolean sourceFirst = sourceId.compareTo(destinationId) <= 0;
        CashAdvance first = lockAdvance(sourceFirst ? sourceId : destinationId);
        CashAdvance second = lockAdvance(sourceFirst ? destinationId : sourceId);
        return sourceFirst ? new CashAdvance[]{first, second} : new CashAdvance[]{second, first};
    }

    private CashAdvance lockAdvance(UUID id) {
        return cashAdvanceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cash advance not found: " + id));
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new BusinessRuleViolationException(
                    "Carry-forward is disabled until Finance confirms the review-cycle rules.");
        }
    }

    private void requireFinance() {
        if (!isFinanceCaller()) {
            throw new BusinessRuleViolationException("Only Finance can approve, reject or reverse a carry-forward.");
        }
    }

    /** Finance, or the manager recorded on the source advance. The requester is checked separately by the caller. */
    private void requireApprover(CashAdvanceCarryForward allocation) {
        if (isFinanceCaller()) {
            return;
        }
        String actor = currentUserService.getEmployeeId();
        String manager = allocation.getSourceAdvance().getManagerId();
        if (isManagerCaller() && sameEmployee(actor, manager)) {
            return;
        }
        throw new BusinessRuleViolationException(
                "Only the source advance's manager or Finance can approve or reject a carry-forward.");
    }

    private void requireStatus(CashAdvanceCarryForward allocation, String expected, String verb) {
        if (!expected.equals(allocation.getStatus())) {
            throw new BusinessRuleViolationException("Only " + expected.toLowerCase(Locale.ROOT) + " carry-forwards can be "
                    + verb + ". Current status: " + allocation.getStatus());
        }
    }

    private void assertSourceCanGiveBalance(CashAdvance source) {
        String status = source.getStatus() == null ? "" : source.getStatus().toUpperCase(Locale.ROOT);
        if ("OVERDUE".equals(status)) {
            throw new BusinessRuleViolationException("An overdue advance cannot be carried forward. Resolve the overdue status first.");
        }
        if (!SOURCE_ALLOWED_STATUSES.contains(status)) {
            throw new BusinessRuleViolationException("The source advance cannot give up balance in status " + source.getStatus() + ".");
        }
        if (outstanding(source).signum() <= 0) {
            throw new BusinessRuleViolationException("The source advance has no outstanding balance to carry forward.");
        }
    }

    /** Approved and not yet disbursed. Settled, closed and disbursed advances are refused. */
    private void assertDestinationEligible(CashAdvance destination) {
        String status = destination.getStatus() == null ? "" : destination.getStatus().toUpperCase(Locale.ROOT);
        if (destination.getDisbursedAt() != null || !"APPROVED".equals(status)) {
            throw new BusinessRuleViolationException("The destination advance must be approved and not yet disbursed. Current status: "
                    + destination.getStatus());
        }
    }

    private void assertSameEmployeeAndCurrency(CashAdvance source, CashAdvance destination) {
        if (source.getEmployeeId() == null || !source.getEmployeeId().equals(destination.getEmployeeId())) {
            throw new BusinessRuleViolationException("Carry-forward is allowed only between advances of the same employee.");
        }
        if (!currencyCode(source).equals(currencyCode(destination))) {
            throw new BusinessRuleViolationException("Carry-forward is allowed only between advances in the same currency.");
        }
    }

    /**
     * Refuses an allocation that would close a loop: following APPLIED allocations from the destination must not
     * reach the source. Visited set guards against any cycle already in the data.
     */
    private void assertNoCycle(UUID sourceId, UUID destinationId) {
        Deque<UUID> queue = new ArrayDeque<>();
        Set<UUID> visited = new HashSet<>();
        queue.add(destinationId);
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            if (current.equals(sourceId)) {
                throw new BusinessRuleViolationException("This carry-forward would create a circular allocation between advances.");
            }
            if (!visited.add(current)) {
                continue;
            }
            for (CashAdvanceCarryForward edge : carryForwardRepository.findBySourceAdvance_AdvanceIdAndStatusIn(
                    current, List.of(CashAdvanceCarryForward.APPLIED))) {
                queue.add(edge.getDestinationAdvance().getAdvanceId());
            }
        }
    }

    private BigDecimal allocatedFrom(UUID sourceAdvanceId) {
        return sum(carryForwardRepository.findBySourceAdvance_AdvanceIdAndStatusIn(sourceAdvanceId, ACTIVE));
    }

    private static BigDecimal sum(Collection<CashAdvanceCarryForward> allocations) {
        return allocations.stream().map(CashAdvanceCarryForward::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The authoritative balance from the records. The cached column is never used for capacity decisions. */
    BigDecimal outstanding(CashAdvance advance) {
        return balanceCalculator.outstanding(advance);
    }

    private CashAdvance findAdvance(UUID id) {
        return cashAdvanceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cash advance not found: " + id));
    }

    private static String currencyCode(CashAdvance advance) {
        return advance.getCurrency() != null && advance.getCurrency().getCurrencyCode() != null
                ? advance.getCurrency().getCurrencyCode()
                : "";
    }

    private static boolean sameEmployee(String actor, String owner) {
        return actor != null && owner != null && actor.trim().equalsIgnoreCase(owner.trim());
    }

    private static String requireReason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.length() < MIN_REASON_LENGTH) {
            throw new IllegalArgumentException("Give a reason of at least " + MIN_REASON_LENGTH + " characters.");
        }
        return trimmed;
    }

    private static BigDecimal requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Enter a carry-forward amount greater than zero.");
        }
        return amount;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean isManagerCaller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_MANAGER".equals(a.getAuthority()));
    }

    private static boolean isFinanceCaller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> {
            String role = a.getAuthority();
            return "ROLE_FINANCE".equals(role) || "ROLE_FINANCE_EXECUTIVE".equals(role) || "ROLE_ADMIN".equals(role);
        });
    }

    CashAdvanceCarryForwardResponse toResponse(CashAdvanceCarryForward c) {
        return new CashAdvanceCarryForwardResponse(
                c.getCarryForwardId(),
                c.getSourceAdvance().getAdvanceId(),
                c.getDestinationAdvance().getAdvanceId(),
                c.getEmployeeId(),
                c.getCurrencyCode(),
                c.getAmount(),
                c.getStatus(),
                c.getReason(),
                c.getRequestedBy(),
                c.getRequestedAt(),
                c.getApprovedBy(),
                c.getApprovedAt(),
                c.getAppliedAt(),
                c.getReversedBy(),
                c.getReversedAt());
    }

}
