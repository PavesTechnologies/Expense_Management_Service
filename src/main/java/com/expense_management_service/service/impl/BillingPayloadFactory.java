package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.BillingPayloadResponse;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.Receipt;

import java.math.BigDecimal;

/** Builds the AR billing payload (v1) from a client-billable line. */
final class BillingPayloadFactory {

    static final int PAYLOAD_VERSION = 1;

    private BillingPayloadFactory() {
    }

    /** BR-TAX-015: cost basis = gross - recoverable tax (base currency). */
    static BigDecimal costBasis(ExpenseLineItem line) {
        BigDecimal gross = line.getBaseAmount() != null ? line.getBaseAmount() : BigDecimal.ZERO;
        BigDecimal recoverable = line.getBaseRecoverableTaxAmount() != null ? line.getBaseRecoverableTaxAmount() : BigDecimal.ZERO;
        return gross.subtract(recoverable);
    }

    static BillingPayloadResponse build(ExpenseLineItem line, String baseCurrencyCode, String handoffStatus, String invoiceReference) {
        var report = line.getReport();
        var project = line.getProject();
        return new BillingPayloadResponse(
                PAYLOAD_VERSION,
                line.getLineItemId(),
                report.getReportId(),
                report.getReportNumber(),
                report.getEmployeeId(),
                line.getExpenseDate(),
                line.getCategory() != null ? line.getCategory().getCategoryName() : null,
                line.getDescription(),
                line.getMerchantName(),
                project != null ? project.getProjectId() : null,
                project != null ? project.getProjectCode() : null,
                project != null ? project.getProjectName() : null,
                line.getResolvedClientId(),
                line.getResolvedClientName(),
                costBasis(line),
                line.getBaseAmount(),
                line.getBaseTaxAmount(),
                line.getBaseRecoverableTaxAmount() != null ? line.getBaseRecoverableTaxAmount() : BigDecimal.ZERO,
                line.getTaxCode(),
                baseCurrencyCode,
                line.getCurrency() != null ? line.getCurrency().getCurrencyCode() : null,
                line.getAmount(),
                line.getReceipts() == null ? java.util.List.of() : line.getReceipts().stream().map(Receipt::getReceiptId).toList(),
                handoffStatus,
                invoiceReference);
    }
}
