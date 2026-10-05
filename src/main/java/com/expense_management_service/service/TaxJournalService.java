package com.expense_management_service.service;

import com.expense_management_service.dto.response.TaxJournalResponse;

import java.time.LocalDate;

public interface TaxJournalService {

    /** Journals for reports approved between {@code from} and {@code to} (inclusive). */
    TaxJournalResponse export(LocalDate from, LocalDate to);
}
