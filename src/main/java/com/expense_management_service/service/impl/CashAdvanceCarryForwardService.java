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
    private final com.expense_management_service.repository.CashAdvanceAdjustmentRepository adjustmentRepository;

    @Value("${expense.carry-forward.enabled:false}")
    private boolean enabled = false;

    /**
     * Expenses submitted against an advance but not yet set against it. Optional so unit tests that build this service
     * directly still work; without it no submitted claims are subtracted.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CashAdvanceUtilizationCalculator utilizationCalculator;

    /** Optional for the same reason; without it the open-report check is skipped. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CashAdvanceSettlementReadiness settlementReadiness;

    /** Statuses whose expenses are still being reviewed. Their status is owned by that review, so a carry-forward keeps it. */
    private static final Set<String> EXPENSE_REVIEW_STATUSES = Set.of("EXPENSE_SUBMITTED", "EXPENSE_VERIFIED");

    // ---------------------------------------------------------------- propose

    @Transactional
    public CashAdvanceCarryForwardResponse propose(CashAdvanceCarryForwardRequest request) {
        requireEnabled();
        String actor = currentUserService.getActorId();
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
        assertSourceHasLinkedExpense(source);
        assertDestinationEligible(destination);
        assertNoCycle(source.getAdvanceId(), destination.getAdvanceId());

        BigDecimal outstanding = outstanding(source);
        BigDecimal alreadyAllocated = proposedFrom(source.getAdvanceId());
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
        String approver = currentUserService.getActorId();
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
        // Applied carry-forwards are already off the balance (carriedOut), so only proposals still waiting count here.
        BigDecimal outstanding = outstanding(source);
        if (proposedFrom(source.getAdvanceId()).compareTo(outstanding) > 0) {
            throw new BusinessRuleViolationException("Active carry-forwards from this advance exceed its verified outstanding balance of "
                    + outstanding + ".");
        }

        applyBalances(source, destination, allocation.getAmount(), approver, reason);

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

    // ---------------------------------------------------------------- held carry-forward (moved now, used by the next advance)

    /**
     * Finance carries part of an advance's balance forward. The money moves at once: it leaves this advance's balance
     * (carriedOut) and is held as the employee's carried-forward outstanding ({@code carryForwardReadyAmount}) until a
     * later advance takes it in, through a Cash Advance + Outstanding rollover or a manager's set-off. There is no time
     * limit, and a held balance is never overdue on its own. Repeated calls add to what is held.
     *
     * <p>Only the part of the balance that submitted expenses do not already cover can move, so expenses still under
     * review can always be set against the advance later without its balance going negative.
     */
    @Transactional
    public CarryForwardReadyResponse markReady(UUID sourceAdvanceId, BigDecimal amount, String reason) {
        requireEnabled();
        String actor = currentUserService.getActorId();
        String why = requireReason(reason);
        BigDecimal requested = requirePositive(amount);
        if (!isFinanceCaller()) {
            throw new BusinessRuleViolationException("Only Finance can carry a balance forward.");
        }
        CashAdvance source = lockAdvance(sourceAdvanceId);
        assertSourceCanGiveBalance(source);
        BigDecimal available = carryableNow(source);
        if (requested.compareTo(available) > 0) {
            // Say how much is held for expenses in approval, and on which reports, when that is what limits it.
            if (settlementReadiness != null && proposedFrom(source.getAdvanceId()).signum() == 0) {
                settlementReadiness.assertWithinSettleable(settlementReadiness.utilization(source), requested, "carried forward");
            }
            throw new BusinessRuleViolationException("Carry-forward of " + requested + " exceeds the unallocated balance of "
                    + available + " on this advance (the balance less expenses already submitted against it).");
        }
        BigDecimal heldBefore = held(source);
        BigDecimal newBalance = CashAdvanceBalanceRules.requireNonNegative(outstanding(source).subtract(requested), decimals(source));
        // Held is set first, so the status rule sees that the advance still has money waiting to move on.
        source.setCarryForwardReadyAmount(heldBefore.add(requested));
        setBalanceKeepingReviewStatus(source, newBalance, "CASH_ADVANCE_CARRIED_FORWARD_OUT", actor, why);
        source.setCarriedOutAmount(CashAdvanceBalanceRules.carriedOut(source).add(requested));
        cashAdvanceRepository.save(source);
        balanceLedger.recordChange("CashAdvance", source.getAdvanceId(), "CARRY_FORWARD_HELD",
                "Held: " + heldBefore, "Held: " + source.getCarryForwardReadyAmount(), actor, why);
        return readyResponse(source);
    }

    /** The carried-forward balance held from this advance, or a response with a null amount when none is held. */
    @Transactional(readOnly = true)
    public CarryForwardReadyResponse readyFor(UUID advanceId) {
        return readyResponse(findAdvance(advanceId));
    }

    /** Returns a held carry-forward to its advance before a later advance has taken it in. Finance only. */
    @Transactional
    public CarryForwardReadyResponse cancelReady(UUID sourceAdvanceId, String reason) {
        requireEnabled();
        String actor = currentUserService.getActorId();
        String why = requireReason(reason);
        if (!isFinanceCaller()) {
            throw new BusinessRuleViolationException("Only Finance can return a carried-forward balance.");
        }
        CashAdvance source = lockAdvance(sourceAdvanceId);
        BigDecimal heldBefore = held(source);
        if (heldBefore.signum() > 0) {
            BigDecimal newBalance = outstanding(source).add(heldBefore);
            source.setCarryForwardReadyAmount(null);
            source.setCarriedOutAmount(CashAdvanceBalanceRules.carriedOut(source).subtract(heldBefore).max(BigDecimal.ZERO));
            setBalanceKeepingReviewStatus(source, newBalance, "CARRY_FORWARD_HELD_RETURNED", actor, why);
            cashAdvanceRepository.save(source);
            balanceLedger.recordChange("CashAdvance", source.getAdvanceId(), "CARRY_FORWARD_HELD_RETURNED",
                    "Held: " + heldBefore, null, actor, why);
        }
        return readyResponse(source);
    }

    /**
     * A manager's set-off: when the receiving advance is approved, the held balance of the advance the manager named moves
     * into it at once, up to the recommended amount and what the receiving advance can carry. Finance already decided to
     * move that money when it carried it forward, so nothing waits for another approval. Other requests take a held
     * balance only through a Cash Advance + Outstanding rollover, so the cash an employee asked for is never cut silently.
     */
    public void applyReadyForDestination(CashAdvance destination) {
        if (!enabled || destination.getEmployeeId() == null
                || !"SET_OFF".equalsIgnoreCase(destination.getRecommendedTreatment())) {
            return;
        }
        String currency = currencyCode(destination);
        String namedSource = destination.getRecommendedSourceAdvanceId();
        BigDecimal limit = destination.getRecommendedSetOffAmount();
        BigDecimal taken = BigDecimal.ZERO;
        for (CashAdvance source : cashAdvanceRepository
                .findByEmployeeIdAndCarryForwardReadyAmountIsNotNullOrderByCreatedAtAsc(destination.getEmployeeId())) {
            if (source.getAdvanceId().equals(destination.getAdvanceId()) || !currency.equals(currencyCode(source))
                    || (namedSource != null && !namedSource.equalsIgnoreCase(source.getAdvanceId().toString()))) {
                continue;
            }
            BigDecimal canCarry = destination.getAmount().subtract(CashAdvanceBalanceRules.carriedIn(destination));
            BigDecimal amount = held(source).min(canCarry);
            if (limit != null) {
                amount = amount.min(limit.subtract(taken));
            }
            if (amount.signum() <= 0) {
                continue;
            }
            lockAdvance(source.getAdvanceId());
            takeHeld(source, destination, amount, AUTO_ACTOR, AUTO_REASON,
                    "set-off:" + destination.getAdvanceId() + ":" + source.getAdvanceId());
            taken = taken.add(amount);
        }
    }

    /** True when the carry-forward feature is switched on ({@code expense.carry-forward.enabled}). */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Cash Advance + Carry Forward: on final approval, every balance Finance carried forward for this employee (same
     * currency, oldest first) moves into the request. The request's amount becomes the new cash plus what moved in, and its
     * carried-in amount records the moved part, so disbursement pays only the new cash. Runs in the caller's transaction,
     * so the approval and the movement commit together. Returns the total moved in.
     *
     * <p>Finance already decided to move this money when it carried it forward, so no further approval is needed. If
     * Finance returned the held balance before this approval, nothing moves and the request is just its new cash.
     */
    @Transactional
    public BigDecimal applyHeldCarryForward(CashAdvance destination, String approver) {
        return applyHeldCarryForward(destination, approver, null);
    }

    /**
     * As above, but moves at most {@code limit}: the carry-forward the request showed when it was submitted and approved.
     * A balance carried forward after submission is not pulled in silently; it waits for the employee's next request.
     * Null means no limit.
     */
    @Transactional
    public BigDecimal applyHeldCarryForward(CashAdvance destination, String approver, BigDecimal limit) {
        requireEnabled();
        if (destination.getDisbursedAt() != null) {
            throw new BusinessRuleViolationException("The request has already been disbursed, so it cannot take a carry-forward.");
        }
        if (!carryForwardRepository.findByDestinationAdvance_AdvanceIdAndStatusIn(destination.getAdvanceId(), ACTIVE).isEmpty()) {
            // Already applied (for example a retried approval). Never move the money twice.
            return CashAdvanceBalanceRules.carriedIn(destination);
        }
        String actor = approver == null || approver.isBlank() ? AUTO_ACTOR : approver;
        String currency = currencyCode(destination);
        List<CashAdvance> sources = new java.util.ArrayList<>();
        for (CashAdvance source : cashAdvanceRepository
                .findByEmployeeIdAndCarryForwardReadyAmountIsNotNullOrderByCreatedAtAsc(destination.getEmployeeId())) {
            if (!source.getAdvanceId().equals(destination.getAdvanceId()) && currency.equals(currencyCode(source))) {
                sources.add(source);
            }
        }
        // Lock in id order, like a rollover, so concurrent movements queue instead of deadlocking.
        List<CashAdvance> lockOrder = new java.util.ArrayList<>(sources);
        lockOrder.sort(java.util.Comparator.comparing(CashAdvance::getAdvanceId));
        java.util.Map<UUID, CashAdvance> locked = new java.util.HashMap<>();
        for (CashAdvance source : lockOrder) {
            locked.put(source.getAdvanceId(), lockAdvance(source.getAdvanceId()));
        }

        // Oldest first, up to the limit.
        java.util.Map<UUID, BigDecimal> take = new java.util.LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (CashAdvance source : sources) {
            BigDecimal amount = held(locked.get(source.getAdvanceId()));
            if (limit != null) {
                amount = amount.min(limit.subtract(total));
            }
            if (amount.signum() > 0) {
                take.put(source.getAdvanceId(), amount);
                total = total.add(amount);
            }
        }
        if (total.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal cash = destination.getAmount() == null ? BigDecimal.ZERO : destination.getAmount();
        BigDecimal newAmount = cash.add(total);
        destination.setAmount(newAmount);
        // The request was created with outstanding = its cash. The employee now accounts for the carried part too, and
        // disbursement keeps an outstanding that is already set, so it must move with the amount here.
        BigDecimal oldOutstanding = destination.getOutstandingBalance();
        destination.setOutstandingBalance(newAmount);
        cashAdvanceRepository.save(destination);
        balanceLedger.recordChange("CashAdvance", destination.getAdvanceId(), "CASH_ADVANCE_CARRY_FORWARD_INCLUDED",
                "Amount: " + cash + ", Outstanding: " + oldOutstanding,
                "Amount: " + newAmount + ", Outstanding: " + newAmount, actor, "Carried-forward balance of " + total + " included");

        String reason = "Carried-forward balance included in Cash Advance + Carry Forward request";
        for (java.util.Map.Entry<UUID, BigDecimal> entry : take.entrySet()) {
            CashAdvance current = locked.get(entry.getKey());
            takeHeld(current, destination, entry.getValue(), actor, reason,
                    "carry-forward-request:" + destination.getAdvanceId() + ":" + current.getAdvanceId());
        }
        return total;
    }

    /**
     * Moves part of a held carry-forward into the receiving advance: the receiving advance carries it in, and the held
     * amount drops. The source balance does not change, because the money left it when it was carried forward.
     */
    private void takeHeld(CashAdvance source, CashAdvance destination, BigDecimal amount, String actor, String reason,
                          String idempotencyKey) {
        if (CashAdvanceBalanceRules.carriedIn(destination).add(amount).compareTo(destination.getAmount()) > 0) {
            throw new BusinessRuleViolationException("Carried-in balance would exceed the receiving advance's amount of "
                    + destination.getAmount() + ".");
        }
        BigDecimal heldBefore = held(source);
        LocalDateTime now = LocalDateTime.now();
        carryForwardRepository.save(CashAdvanceCarryForward.builder()
                .sourceAdvance(source)
                .destinationAdvance(destination)
                .employeeId(source.getEmployeeId())
                .currencyCode(currencyCode(source))
                .amount(amount)
                .status(CashAdvanceCarryForward.APPLIED)
                .reason(reason)
                .idempotencyKey(idempotencyKey)
                .authorizationType("STANDARD")
                .requestedBy(actor)
                .requestedAt(now)
                .approvedBy(actor)
                .approvedAt(now)
                .appliedAt(now)
                .build());

        source.setCarryForwardReadyAmount(CashAdvanceAllocationRules.readyAfterMove(heldBefore, amount));
        balanceLedger.recordChange("CashAdvance", source.getAdvanceId(), "CARRY_FORWARD_HELD_USED",
                "Held: " + heldBefore, "Held: " + source.getCarryForwardReadyAmount(), actor, reason);
        // Once nothing is held, the advance's status follows its own balance again (it may now close).
        if (source.getCarryForwardReadyAmount() == null) {
            setBalanceKeepingReviewStatus(source, outstanding(source), "CASH_ADVANCE_STATUS_RECHECKED", actor, reason);
        }
        cashAdvanceRepository.save(source);

        BigDecimal oldCarried = CashAdvanceBalanceRules.carriedIn(destination);
        BigDecimal newCarried = oldCarried.add(amount);
        destination.setCarriedInAmount(newCarried);
        cashAdvanceRepository.save(destination);
        balanceLedger.recordChange("CashAdvance", destination.getAdvanceId(), "CASH_ADVANCE_CARRIED_FORWARD_IN",
                "CarriedIn: " + oldCarried, "CarriedIn: " + newCarried, actor, reason);
    }

    /** The balance that can be carried forward now: outstanding, less proposals waiting and expenses already submitted. */
    private BigDecimal carryableNow(CashAdvance source) {
        return outstanding(source)
                .subtract(proposedFrom(source.getAdvanceId()))
                .subtract(unsettledClaims(source))
                .max(BigDecimal.ZERO);
    }

    /** Expenses on the linked reports that are not yet set against the advance. They will lower its balance when approved. */
    private BigDecimal unsettledClaims(CashAdvance source) {
        if (utilizationCalculator == null) {
            return BigDecimal.ZERO;
        }
        // Claims on linked reports not approved yet. The same figure limits salary deduction, so both settle the same part.
        BigDecimal inApproval = utilizationCalculator.calculate(source).inApprovalAmount();
        return inApproval == null ? BigDecimal.ZERO : inApproval.max(BigDecimal.ZERO);
    }

    private static BigDecimal held(CashAdvance advance) {
        BigDecimal held = advance.getCarryForwardReadyAmount();
        return held == null || held.signum() < 0 ? BigDecimal.ZERO : held;
    }

    private static int decimals(CashAdvance advance) {
        return advance.getCurrency() != null ? advance.getCurrency().getDecimalPlaces() : 2;
    }

    /**
     * Sets the balance and, unless the advance's expenses are under review, the status that balance implies. An advance in
     * expense review keeps its status: the review decides it, and changing it here would take the report out of review.
     */
    private void setBalanceKeepingReviewStatus(CashAdvance advance, BigDecimal balance, String action, String actor, String reason) {
        String status = advance.getStatus() == null ? "" : advance.getStatus().toUpperCase(Locale.ROOT);
        if (EXPENSE_REVIEW_STATUSES.contains(status)) {
            BigDecimal old = advance.getOutstandingBalance();
            BigDecimal rounded = CashAdvanceBalanceRules.requireNonNegative(balance, decimals(advance));
            advance.setOutstandingBalance(rounded);
            balanceLedger.recordChange("CashAdvance", advance.getAdvanceId(), action,
                    "Balance: " + old, "Balance: " + rounded, actor, reason);
            return;
        }
        balanceLedger.applyBalance(advance, balance, action, actor, reason);
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
        String actor = currentUserService.getActorId();
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
        String actor = currentUserService.getActorId();
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

    /**
     * One source's share of a rollover: {@code fromHeld} comes out of a balance Finance already carried forward, and
     * {@code fromBalance} out of the advance's own open balance.
     */
    private record RolloverTake(CashAdvance source, BigDecimal fromHeld, BigDecimal fromBalance, boolean overdue) {
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
        // Sources: advances whose own balance may be rolled, and every advance holding a carried-forward balance. A held
        // balance has already left its advance, so it can be taken whatever that advance's status is.
        java.util.Map<UUID, CashAdvance> byId = new java.util.LinkedHashMap<>();
        for (CashAdvance advance : cashAdvanceRepository.findByEmployeeIdAndStatusIn(employeeId, ROLLOVER_LOOKUP_STATUSES)) {
            byId.put(advance.getAdvanceId(), advance);
        }
        for (CashAdvance advance : cashAdvanceRepository.findByEmployeeIdAndCarryForwardReadyAmountIsNotNullOrderByCreatedAtAsc(employeeId)) {
            byId.putIfAbsent(advance.getAdvanceId(), advance);
        }
        List<CashAdvance> candidates = new java.util.ArrayList<>();
        for (CashAdvance advance : byId.values()) {
            if (advance.getAdvanceId().equals(destination.getAdvanceId()) || !currency.equals(currencyCode(advance))) {
                continue;
            }
            if (held(advance).signum() > 0 || balanceMayRoll(advance, overdue)) {
                candidates.add(advance);
            }
        }
        if (overdue != null && candidates.stream().noneMatch(a -> a.getAdvanceId().equals(overdue.sourceAdvanceId())
                && balanceMayRoll(a, overdue))) {
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
            // Money Finance already carried forward is used first; then the advance's own balance, where allowed.
            BigDecimal fromHeld = held(source).min(remaining);
            remaining = remaining.subtract(fromHeld);
            BigDecimal fromBalance = BigDecimal.ZERO;
            if (remaining.signum() > 0 && balanceMayRoll(source, overdue)) {
                // Applied carry-forwards are already off the balance, so only proposals still waiting are subtracted.
                BigDecimal free = outstanding(source).subtract(proposedFrom(source.getAdvanceId()));
                if (overdueSource) {
                    free = free.min(overdue.amount());
                }
                fromBalance = free.max(BigDecimal.ZERO).min(remaining);
                remaining = remaining.subtract(fromBalance);
            }
            if (fromHeld.signum() > 0 || fromBalance.signum() > 0) {
                plan.add(new RolloverTake(source, fromHeld, fromBalance, overdueSource && fromBalance.signum() > 0));
            }
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
            if (take.fromHeld().signum() > 0) {
                takeHeld(take.source(), destination, take.fromHeld(), approver, reason,
                        "rollover-held:" + destination.getAdvanceId() + ":" + take.source().getAdvanceId());
            }
            if (take.fromBalance().signum() <= 0) {
                continue;
            }
            carryForwardRepository.save(CashAdvanceCarryForward.builder()
                    .sourceAdvance(take.source())
                    .destinationAdvance(destination)
                    .employeeId(employeeId)
                    .currencyCode(currency)
                    .amount(take.fromBalance())
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
            applyBalances(take.source(), destination, take.fromBalance(), approver, reason);
        }
        return rolloverAmount;
    }

    /** True when the advance's own open balance may be rolled: an eligible status, or OVERDUE with Finance's exception. */
    private static boolean balanceMayRoll(CashAdvance advance, OverdueException overdue) {
        boolean overdueStatus = "OVERDUE".equalsIgnoreCase(advance.getStatus());
        return overdueStatus
                ? overdue != null && overdue.sourceAdvanceId().equals(advance.getAdvanceId())
                : CashAdvanceAllocationRules.rolloverEligible(advance.getStatus());
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
        String actor = currentUserService.getActorId();
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
        String approver = currentUserService.getActorId();
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
        String actor = currentUserService.getActorId();
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

    /**
     * Proposals from this advance still waiting for a decision. Applied carry-forwards are not counted: they have already
     * left the balance through carriedOut, so subtracting them again would count the same money twice.
     */
    private BigDecimal proposedFrom(UUID sourceAdvanceId) {
        return sum(carryForwardRepository.findBySourceAdvance_AdvanceIdAndStatusIn(
                sourceAdvanceId, List.of(CashAdvanceCarryForward.PROPOSED)));
    }

    private static BigDecimal sum(Collection<CashAdvanceCarryForward> allocations) {
        return allocations.stream().map(CashAdvanceCarryForward::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The authoritative balance from the records. The cached column is never used for capacity decisions. */
    BigDecimal outstanding(CashAdvance advance) {
        return balanceCalculator.outstanding(advance);
    }

    /**
     * A carry-forward moves the source's balance out of it, which closes the source. An advance with no expense linked
     * has nothing to reconcile against, so it cannot be settled this way.
     */
    private void assertSourceHasLinkedExpense(CashAdvance source) {
        boolean linked = adjustmentRepository.findByCashAdvance_AdvanceId(source.getAdvanceId()).stream()
                .anyMatch(com.expense_management_service.entity.CashAdvanceAdjustment::isActive);
        if (!linked) {
            throw new BusinessRuleViolationException("Link at least one expense to the source advance before carrying its "
                    + "balance forward. Without a linked expense, the advance cannot be closed.");
        }
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
