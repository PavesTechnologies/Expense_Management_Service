package com.expense_management_service.enums;

/**
 * How far an {@code AMOUNT_LIMIT} violation's actual value deviates from its limit, computed per
 * violation from the overage percentage - not to be confused with {@link PolicySeverity}, which is
 * a static, admin-set signal-strength tag on the rule itself (e.g. DUPLICATE_EXPENSE defaults to
 * INFO). The same rule can produce a {@code MINOR} tier for one expense and a {@code SEVERE} tier
 * for another; {@code PolicySeverity} never changes per expense. Named {@code PolicyOverageTier}
 * rather than reusing "severity" as a type name specifically to keep the two unambiguous in code,
 * even though the API field exposing this is called {@code severityTier}, matching the original
 * design vocabulary.
 */
public enum PolicyOverageTier {
    MINOR,
    MODERATE,
    SEVERE
}
