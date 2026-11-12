package com.expense_management_service.service;

import com.expense_management_service.dto.response.ExpenseSummaryReportResponse;

import java.time.LocalDate;

public interface ExpenseReportingService {

    /**
     * Summary for {@code from}..{@code to} inclusive (defaults: the last 12 months). Organization-wide
     * for Finance/Admin; the caller's direct reports for a manager.
     */
    ExpenseSummaryReportResponse summary(LocalDate from, LocalDate to);
}
