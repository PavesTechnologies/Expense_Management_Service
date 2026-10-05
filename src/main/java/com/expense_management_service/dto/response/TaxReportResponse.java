package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Tax analysis over submitted expense lines (design §18), grouped by month, tax code, component
 * or category. Amounts are base currency. Lines entered before tax codes group as LEGACY.
 * For groupBy=component, gross / net are null (a component carries tax only).
 */
public record TaxReportResponse(
        LocalDate from,
        LocalDate to,
        String groupBy,
        String currency,
        List<Row> rows,
        Row totals
) {
    public record Row(String key, String label, long lineCount, BigDecimal grossAmount, BigDecimal taxAmount,
                      BigDecimal recoverableTaxAmount, BigDecimal nonRecoverableTaxAmount, BigDecimal netAmount) {
    }
}
