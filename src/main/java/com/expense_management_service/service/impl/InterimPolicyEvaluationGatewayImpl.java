package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.PolicyViolation;
import com.expense_management_service.enums.PolicyEnforcementType;
import com.expense_management_service.mapper.PolicyViolationMapper;
import com.expense_management_service.repository.PolicyViolationRepository;
import com.expense_management_service.service.PolicyDecision;
import com.expense_management_service.service.PolicyEvaluationGateway;
import com.expense_management_service.service.PolicyEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Interim {@link PolicyEvaluationGateway} adapter wrapping the existing, advisory-only
 * {@link PolicyEvaluator} until the separately-built Policy Engine rebuild lands. Re-runs
 * evaluation across every line item at submission time (mirrors EP06's {@code
 * refreshPolicyViolationsForReport}, including justification carry-over), wrapped defensively so a
 * policy failure can never block a submission - on top of {@code PolicyEvaluator}'s own
 * never-throw contract.
 * <p>
 * BLOCK enforcement (Finance Verification Phase 0 decision): this previously always returned
 * {@code allowed = true} regardless of {@code PolicyEnforcementType.BLOCK} violations - a genuine
 * gap, not a documented simplification, confirmed by there being zero non-test references to
 * {@code BLOCK} that actually blocked anything. Fixed here because Finance Verification's
 * eligibility checker needs "policy exception resolved" to mean something real: a checker that
 * appears to enforce BLOCK while this gateway silently allowed it through would be worse than not
 * checking at all. Uses {@code PolicyViolationRepository}'s existing "Block gate" fast-path query -
 * that method already existed, unused, suggesting this fix was anticipated but never wired in.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class InterimPolicyEvaluationGatewayImpl implements PolicyEvaluationGateway {

    private final PolicyEvaluator policyEvaluator;
    private final PolicyViolationRepository policyViolationRepository;
    private final PolicyViolationMapper policyViolationMapper;

    @Override
    public PolicyDecision evaluate(ExpenseReport report) {
        List<PolicyViolation> current = refreshViolations(report);
        var display = current.stream().map(policyViolationMapper::toResponse).toList();

        boolean hasBlockViolation = policyViolationRepository
                .existsByLineItem_Report_ReportIdAndEnforcementType(report.getReportId(), PolicyEnforcementType.BLOCK);
        return new PolicyDecision(!hasBlockViolation, display);
    }

    private List<PolicyViolation> refreshViolations(ExpenseReport report) {
        try {
            List<PolicyViolation> all = new java.util.ArrayList<>();
            for (ExpenseLineItem lineItem : report.getExpenseLineItems()) {
                List<PolicyViolation> existing = policyViolationRepository.findByLineItem_LineItemId(lineItem.getLineItemId());
                List<PolicyViolation> recomputed = policyEvaluator.evaluate(lineItem);

                for (PolicyViolation violation : recomputed) {
                    existing.stream()
                            .filter(old -> sameRule(old, violation))
                            .findFirst()
                            .ifPresent(old -> {
                                violation.setJustification(old.getJustification());
                                violation.setJustifiedAt(old.getJustifiedAt());
                            });
                }

                policyViolationRepository.deleteAll(existing);
                all.addAll(policyViolationRepository.saveAll(recomputed));
            }
            return all;
        } catch (Exception ex) {
            log.warn("Policy evaluation failed while submitting report {} - continuing without refreshing policy warnings",
                    report.getReportId(), ex);
            return List.of();
        }
    }

    private boolean sameRule(PolicyViolation existing, PolicyViolation recomputed) {
        if (existing.getRuleType() != recomputed.getRuleType()) {
            return false;
        }
        var existingRuleId = existing.getPolicyRule() != null ? existing.getPolicyRule().getPolicyId() : null;
        var recomputedRuleId = recomputed.getPolicyRule() != null ? recomputed.getPolicyRule().getPolicyId() : null;
        return Objects.equals(existingRuleId, recomputedRuleId);
    }
}
