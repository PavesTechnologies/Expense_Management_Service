package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.response.TaxJournalResponse;
import com.expense_management_service.service.TaxJournalService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Tax journal export (read model for the ERP). from/to = approval dates; default: this month to date. */
@RestController
@RequiredArgsConstructor
public class TaxJournalController {

    private final TaxJournalService taxJournalService;

    @GetMapping("/xms/finance/tax-journal")
    @PreAuthorize("hasAnyRole('FINANCE_EXECUTIVE','FINANCE','ADMIN','SUPER_ADMIN')")
    public ApiResponse<TaxJournalResponse> taxJournal(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(taxJournalService.export(from, to));
    }
}
