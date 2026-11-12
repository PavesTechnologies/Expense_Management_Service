package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.PolicyViolation;
import com.expense_management_service.mapper.PolicyViolationMapper;
import com.expense_management_service.repository.PolicyViolationRepository;
import com.expense_management_service.service.CrossDepartmentAllocationService;
import com.expense_management_service.service.PolicyDecision;
import com.expense_management_service.service.PolicyEvaluationGateway;
import com.expense_management_service.service.PolicyEvaluator;
import com.expense_management_service.service.ReceiptDuplicateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Interim {@link PolicyEvaluationGateway} adapter wrapping the existing, advisory-only
 * {@link PolicyEvaluator} until the separately-built Policy Engine rebuild lands. Re-runs
 * evaluation across every line item at submission time (mirrors EP06's {@code
 * refreshPolicyViolationsForReport}, including justification carry-over), wrapped defensively so a
 * policy failure can never block a submission - on top of {@code PolicyEvaluator}'s own
 * never-throw contract.
 * <p>
 * Justification gate: a violation (WARN or BLOCK) stops submission only until the employee has
 * explained it. Once every violation on the report carries a justification the report goes
 * through, and the approver and Finance read the explanation and decide - approve, send back or
 * reject. A BLOCK rule therefore means "must be explained before it can be submitted", not "can
 * never be submitted"; it is the same definition {@code DefaultFinanceVerificationEligibilityCheckerImpl}
 * uses for "policy exception resolved".
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class InterimPolicyEvaluationGatewayImpl implements PolicyEvaluationGateway {

    private final PolicyEvaluator policyEvaluator;
    private final PolicyViolationRepository policyViolationRepository;
    private final PolicyViolationMapper policyViolationMapper;
    private final ReceiptDuplicateService receiptDuplicateService;
    private final CrossDepartmentAllocationService crossDepartmentAllocationService;

    @Override
    public PolicyDecision evaluate(ExpenseReport report) {
        List<PolicyViolation> current = refreshViolations(report);
        var display = current.stream().map(policyViolationMapper::toResponse).toList();

        // Unlike policy violations, a receipt already claimed elsewhere (same file, or same invoice
        // from the same supplier) can't be explained away - the same bill is never paid twice.
        List<ReceiptDuplicateService.DuplicateMatch> duplicates = receiptDuplicateService.findBlockingMatchesForReport(report);
        if (!duplicates.isEmpty()) {
            return new PolicyDecision(false, display, duplicateReceiptReason(duplicates));
        }

        List<String> missingAllocationReasons = crossDepartmentAllocationService.findLinesMissingReason(report);
        if (!missingAllocationReasons.isEmpty()) {
            return new PolicyDecision(false, display, String.join(" ", missingAllocationReasons));
        }

        List<String> unexplained = policyViolationRepository.findByLineItem_Report_ReportId(report.getReportId()).stream()
                .filter(violation -> !violation.isJustified())
                .map(violation -> violation.getMessage() != null ? violation.getMessage() : String.valueOf(violation.getRuleType()))
                .toList();
        if (unexplained.isEmpty()) {
            return new PolicyDecision(true, display);
        }
        return new PolicyDecision(false, display, "Explain each policy violation before submitting ("
                + unexplained.size() + " without a justification): " + String.join("; ", unexplained));
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
                                violation.setJustifiedBy(old.getJustifiedBy());
                                // Carrying these forward is what makes an approver's exception
                                // authorization durable across a correction + resubmission cycle -
                                // every submit()/resubmitCorrection() deletes and recomputes every
                                // violation on the report, so without this an approver's already-
                                // recorded approve-exception would be silently destroyed the next
                                // time the report is corrected, even for a rule wholly unrelated to
                                // what was corrected.
                                violation.setApproverJustification(old.getApproverJustification());
                                violation.setApproverJustifiedBy(old.getApproverJustifiedBy());
                                violation.setApproverJustifiedAt(old.getApproverJustifiedAt());
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

    /** One sentence per duplicated receipt, e.g. "Receipt 'bill.jpg': This exact receipt file has already been claimed on ...". */
    private String duplicateReceiptReason(List<ReceiptDuplicateService.DuplicateMatch> duplicates) {
        Map<UUID, ReceiptDuplicateService.DuplicateMatch> firstPerReceipt = new LinkedHashMap<>();
        duplicates.forEach(match -> firstPerReceipt.putIfAbsent(match.receiptId(), match));
        String details = firstPerReceipt.values().stream()
                .map(match -> "Receipt '" + match.receiptFileName() + "': " + match.message())
                .collect(Collectors.joining(" "));
        return "This report can't be submitted because it contains a receipt that has already been claimed. "
                + "Remove the duplicate receipt or its expense, then submit again. " + details;
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
