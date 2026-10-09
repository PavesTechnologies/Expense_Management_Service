package com.expense_management_service.service;

import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.PolicyViolation;

import java.util.List;

/**
 * Evaluates the active, in-effect {@code PolicyRule}s configured for a line item's category and
 * returns any violations detected. EP05 is advisory-only: this contract must hold for every
 * implementation —
 * <ul>
 *   <li><b>Never throws.</b> Any internal failure (a malformed rule, a lookup error) is caught,
 *       logged, and treated as "that rule produced no violation" — it must never prevent the
 *       caller from saving the line item.</li>
 *   <li>A malformed single rule (e.g. a non-numeric {@code ruleValue}) is skipped in isolation;
 *       it must not suppress evaluation of the line item's other rules.</li>
 *   <li>Returns unsaved {@link PolicyViolation} instances — the caller is responsible for
 *       persistence (see {@code ExpenseLineItemServiceImpl}, which preserves prior justifications
 *       across a recompute).</li>
 * </ul>
 */
public interface PolicyEvaluator {

    List<PolicyViolation> evaluate(ExpenseLineItem lineItem);
}
