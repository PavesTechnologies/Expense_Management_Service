package com.expense_management_service.service;

import com.expense_management_service.dto.response.DashboardResponse;
import com.expense_management_service.dto.response.ItcRegisterResponse;
import com.expense_management_service.dto.response.TaxReportResponse;
import com.expense_management_service.enums.ItcStatus;

import java.time.LocalDate;
import java.util.UUID;

public interface TaxReportService {

    /** @param groupBy month | taxCode | component | category */
    TaxReportResponse report(LocalDate from, LocalDate to, String groupBy, UUID categoryId, UUID taxCodeId);

    /** Tax KPIs for the dashboards, with the last {@code months} months as a series. */
    DashboardResponse.TaxSummary summary(int months);

    /**
     * Line-level input tax credit register over submitted lines with tax (expense date in the
     * window; default: this month to date). {@code itcStatus} null = every line.
     */
    ItcRegisterResponse itcRegister(LocalDate from, LocalDate to, ItcStatus itcStatus, UUID categoryId, UUID taxCodeId);

    /**
     * Marks the lines' input tax credit as claimed in the GST return for {@code returnPeriod} (yyyy-MM). Every line must
     * be recoverable, verified by Finance, have a supplier GSTIN and not be claimed already; otherwise nothing is marked
     * and the error names the lines. Each change is written to the tax audit log.
     *
     * @return the number of lines marked
     */
    int claimItc(java.util.List<UUID> lineItemIds, String returnPeriod, String note, String employeeId);

    /** Undoes a claim (e.g. marked in the wrong month); {@code reason} is required and audited. */
    int unclaimItc(java.util.List<UUID> lineItemIds, String reason, String employeeId);
}
