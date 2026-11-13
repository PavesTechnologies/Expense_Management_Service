package com.expense_management_service.service.impl;

import java.math.BigDecimal;

/**
 * Employee exposure against the company's configured limit.
 *
 * <p>Exposure is what the company could still be owed or has committed to pay the employee:
 * <ul>
 *   <li>{@code outstanding}: the ledger balance of every open advance (disbursed, not yet settled).</li>
 *   <li>{@code committed}: approved or pending advances that are not disbursed yet.</li>
 *   <li>{@code requested}: the advance being decided.</li>
 * </ul>
 * {@code potential = outstanding + committed + requested}. The request is flagged when that exceeds the limit. With
 * no limit configured nothing is flagged, but the figures are still returned.
 *
 * <p>This class holds only arithmetic, so it can be tested without the Spring context. Amounts use {@link BigDecimal}.
 */
public final class CashAdvanceExposure {

    private CashAdvanceExposure() {
    }

    public record Result(
            BigDecimal limit,
            boolean limitConfigured,
            BigDecimal outstanding,
            BigDecimal committed,
            BigDecimal requested,
            BigDecimal potential,
            BigDecimal excessOverLimit,
            boolean exceedsLimit
    ) {
    }

    public static Result evaluate(BigDecimal limit, BigDecimal outstanding, BigDecimal committed, BigDecimal requested) {
        BigDecimal out = nz(outstanding);
        BigDecimal com = nz(committed);
        BigDecimal req = nz(requested);
        BigDecimal potential = out.add(com).add(req);
        boolean configured = limit != null && limit.signum() > 0;
        BigDecimal excess = configured && potential.compareTo(limit) > 0
                ? potential.subtract(limit)
                : BigDecimal.ZERO;
        return new Result(configured ? limit : null, configured, out, com, req, potential, excess, excess.signum() > 0);
    }

    /** Parses the configured limit. Blank, zero, negative or malformed values mean no limit. */
    public static BigDecimal parseLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(raw.trim());
            return v.signum() > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
