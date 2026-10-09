package com.expense_management_service.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Tax journal read model for the ERP (design Phase 7). Per approved report:
 * Dr expense GL (net + non-recoverable tax), Dr input tax GL (recoverable tax), Cr employee
 * payable (gross). Base currency; every journal balances because net + tax = gross on each line.
 * Cash advance settlement is a separate entry and not included.
 */
public record TaxJournalResponse(
        LocalDate from,
        LocalDate to,
        String currency,
        List<Journal> journals,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        /** Lines whose category or tax code has no GL account mapped - they post to UNMAPPED. */
        List<String> warnings
) {
    public record Journal(UUID reportId, String reportNumber, String employeeId, LocalDate journalDate,
                          List<Entry> entries, BigDecimal totalDebit, BigDecimal totalCredit, boolean balanced) {
    }

    /** One posting line; exactly one of debit / credit is non-zero. */
    public record Entry(String accountCode, String accountName, String entryType, BigDecimal debit, BigDecimal credit, String memo) {
    }
}
