package com.expense_management_service.service;

import com.expense_management_service.dto.response.DashboardResponse;
import com.expense_management_service.dto.response.TaxReportResponse;

import java.time.LocalDate;
import java.util.UUID;

public interface TaxReportService {

    /** @param groupBy month | taxCode | component | category */
    TaxReportResponse report(LocalDate from, LocalDate to, String groupBy, UUID categoryId, UUID taxCodeId);

    /** Tax KPIs for the dashboards, with the last {@code months} months as a series. */
    DashboardResponse.TaxSummary summary(int months);
}
