package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.enums.TaxValidationStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which lines' input tax credit may be marked as claimed in a GST return. */
class ItcClaimRulesTest {

    private static ExpenseLineItem line(String recoverable, TaxValidationStatus status, String gstin, String claimedIn) {
        ExpenseLineItem l = new ExpenseLineItem();
        l.setBaseRecoverableTaxAmount(new BigDecimal(recoverable));
        l.setTaxValidationStatus(status);
        l.setSupplierGstin(gstin);
        l.setItcClaimPeriod(claimedIn);
        return l;
    }

    @Test
    void verifiedRecoverableLineWithGstinCanBeClaimed() {
        assertNull(TaxReportServiceImpl.claimProblem(line("1200", TaxValidationStatus.FINANCE_ADJUSTED, "29ABCDE1234F1Z5", null)));
        assertNull(TaxReportServiceImpl.claimProblem(line("10", TaxValidationStatus.FINANCE_VERIFIED, "29ABCDE1234F1Z5", null)));
    }

    @Test
    void everyOtherLineSaysWhyNot() {
        assertEquals("no supplier GSTIN", TaxReportServiceImpl.claimProblem(line("1200", TaxValidationStatus.FINANCE_ADJUSTED, null, null)));
        assertEquals("Finance has not verified the tax yet",
                TaxReportServiceImpl.claimProblem(line("1200", TaxValidationStatus.MATCHED, "29ABCDE1234F1Z5", null)));
        assertEquals("no recoverable tax", TaxReportServiceImpl.claimProblem(line("0", TaxValidationStatus.FINANCE_VERIFIED, "29ABCDE1234F1Z5", null)));
        assertEquals("already claimed in 2026-11",
                TaxReportServiceImpl.claimProblem(line("1200", TaxValidationStatus.FINANCE_VERIFIED, "29ABCDE1234F1Z5", "2026-11")));
    }
}
