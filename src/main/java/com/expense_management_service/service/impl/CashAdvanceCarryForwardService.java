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

/**
 * Carry-forward: an unspent balance on a source advance is allocated to a destination advance.
 *
 * <p>Rules (decision record): a proposal comes from the employee who owns the source or from Finance, with a reason.
 * A Finance user other than the requester approves it. The combined active allocations from one source stay within
 * its verified outstanding balance. The destination must be APPROVED and not yet disbursed. A carry-forward never
 * clears an OVERDUE status or changes a deadline, so an overdue source is refused. Circular allocations are refused.
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

    /** Source statuses that can give up balance. OVERDUE is excluded so a carry-forward cannot clear it. */
    static final Set<String> SOURCE_ALLOWED_STATUSES = Set.of(
            "DISBURSED", "IN_PROGRESS", "SETTLEMENT_PENDING", "PARTIALLY_SETTLED",
            "PARTIALLY_ADJUSTED", "ADJUSTED", "EXPENSE_SUBMITTED", "EXPENSE_VERIFIED");

    private static final List<String> ACTIVE = List.of(CashAdvanceCarryForward.PROPOSED, CashAdvanceCarryForward.APPLIED);

    private final CashAdvanceRepository cashAdvanceRepository;
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
        requireFinance();
        String approver = currentUserService.getEmployeeId();
        String reason = requireReason(decision.reason());

        CashAdvanceCarryForward allocation = lockAllocation(carryForwardId);
        if (CashAdvanceCarryForward.APPLIED.equals(allocation.getStatus())) {
            return toResponse(allocation);
        }
        requireStatus(allocation, CashAdvanceCarryForward.PROPOSED, "approved");

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

        int decimals = source.getCurrency() != null ? source.getCurrency().getDecimalPlaces() : 2;
        BigDecimal newSourceBalance = CashAdvanceBalanceRules.requireNonNegative(outstanding.subtract(allocation.getAmount()), decimals);
        balanceLedger.applyBalance(source, newSourceBalance, "CASH_ADVANCE_CARRIED_FORWARD_OUT", approver, reason);
        cashAdvanceRepository.save(source);

        BigDecimal oldCarried = CashAdvanceBalanceRules.carriedIn(destination);
        BigDecimal newCarried = oldCarried.add(allocation.getAmount());
        destination.setCarriedInAmount(newCarried);
        cashAdvanceRepository.save(destination);
        balanceLedger.recordChange("CashAdvance", destination.getAdvanceId(), "CASH_ADVANCE_CARRIED_FORWARD_IN",
                "CarriedIn: " + oldCarried, "CarriedIn: " + newCarried, approver, reason);

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

    // ---------------------------------------------------------------- reject

    @Transactional
    public CashAdvanceCarryForwardResponse reject(UUID carryForwardId, CarryForwardDecisionRequest decision) {
        requireEnabled();
        requireFinance();
        String actor = currentUserService.getEmployeeId();
        String reason = requireReason(decision.reason());

        CashAdvanceCarryForward allocation = lockAllocation(carryForwardId);
        requireStatus(allocation, CashAdvanceCarryForward.PROPOSED, "rejected");

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

    static BigDecimal outstanding(CashAdvance advance) {
        return advance.getOutstandingBalance() != null ? advance.getOutstandingBalance() : advance.getAmount();
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
