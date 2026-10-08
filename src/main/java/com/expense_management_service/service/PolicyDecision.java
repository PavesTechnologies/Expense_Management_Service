package com.expense_management_service.service;

import com.expense_management_service.dto.response.PolicyWarningResponse;

import java.util.List;

/**
 * The coarse, extensible result of a {@link PolicyEvaluationGateway} call. {@code allowed} is the
 * ONLY thing the Approval Engine branches its own logic on (§10.1) - {@code violations} is passed
 * through purely as display metadata (§10.4, policy warnings per pending task), never inspected for
 * its specific rule content. {@code blockedReason} is the employee-facing explanation of why
 * {@code allowed} is false (null when allowed).
 */
public record PolicyDecision(boolean allowed, List<PolicyWarningResponse> violations, String blockedReason) {

    public PolicyDecision(boolean allowed, List<PolicyWarningResponse> violations) {
        this(allowed, violations, null);
    }
}
