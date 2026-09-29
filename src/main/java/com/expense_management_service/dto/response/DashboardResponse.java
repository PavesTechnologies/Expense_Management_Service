package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One role's dashboard. Every role fills the same building blocks with its own data, so the
 * frontend renders them with one set of widgets; a section a role doesn't use is an empty list.
 * All amounts are in the organization base currency ({@code baseCurrencyCode}), summed from line
 * items' {@code baseAmount} — reports can be in different currencies, so their own totals can't
 * be added together.
 *
 * @param view      employee / manager / finance / ap / admin
 * @param kpis      headline numbers, in display order
 * @param trend     one point per month, oldest first
 * @param pipeline  ordered stages with a count each (where things are in the workflow)
 * @param breakdown part-to-whole slices (status, decision mix, ...), named by {@code breakdownTitle}
 * @param ranking   top-N items by amount (categories, cost centers), named by {@code rankingTitle}
 * @param aging     how long open items have been waiting, in fixed buckets
 * @param budgets   cost-center budget utilization (admin only)
 * @param attention items that need someone to act, most urgent first
 * @param activity  most recent events, newest first
 */
public record DashboardResponse(
        String view,
        String baseCurrencyCode,
        List<Kpi> kpis,
        String trendTitle,
        List<TrendPoint> trend,
        List<Stage> pipeline,
        String breakdownTitle,
        List<Slice> breakdown,
        String rankingTitle,
        List<Slice> ranking,
        List<Slice> aging,
        List<Budget> budgets,
        List<Item> attention,
        List<Item> activity
) {

    /** @param format "count", "money" or "days" — tells the frontend how to print {@code value}. */
    public record Kpi(String key, String label, BigDecimal value, String format, String hint, String tone) {
    }

    /**
     * @param primary   the main measure (amount, or a count when {@code primaryFormat} is count)
     * @param count     number of items in the month
     * @param secondary an optional second count for the same month (e.g. sent back vs approved)
     */
    public record TrendPoint(String period, String label, BigDecimal primary, long count, long secondary) {
    }

    public record Stage(String key, String label, long count) {
    }

    public record Slice(String key, String label, long count, BigDecimal amount) {
    }

    public record Budget(String costCenterName, String fiscalYear, BigDecimal budgetAmount, BigDecimal usedAmount, BigDecimal warningThreshold) {
    }

    /** A report-level row for the attention / activity lists. */
    public record Item(UUID reportId, String reportNumber, String title, String employeeId, String status,
                       BigDecimal amount, LocalDateTime at, String note) {
    }
}
