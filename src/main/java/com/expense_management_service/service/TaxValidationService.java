package com.expense_management_service.service;

import com.expense_management_service.enums.TaxValidationStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * Compares a line's final tax with its tax code's calculation and with the tax OCR read off the
 * receipt, and gives Finance one status to act on (design §11 / §25). Tolerances come from
 * system_configuration (TAX_OCR_TOLERANCE_ABSOLUTE / _PERCENT, TAX_OCR_MIN_CONFIDENCE).
 */
public interface TaxValidationService {

    String REASON_OCR_MISMATCH = "OCR_MISMATCH";
    String REASON_CONFIG_MISMATCH = "CONFIG_MISMATCH";
    String REASON_OCR_UNAVAILABLE = "OCR_UNAVAILABLE";
    String REASON_LOW_OCR_CONFIDENCE = "LOW_OCR_CONFIDENCE";

    /**
     * @param hasTaxCode        a tax code applies to the line
     * @param taxAmount         the line's final tax
     * @param calculatedTax     the code's calculation (null without a code)
     * @param roundingTolerance entered-vs-calculated difference still counted as rounding
     * @param ocrTax            tax read by OCR (null = no OCR)
     * @param ocrConfidence     OCR's confidence in it, 0-1 (null = unknown, treated as usable)
     */
    record Input(boolean hasTaxCode, BigDecimal taxAmount, BigDecimal calculatedTax, BigDecimal roundingTolerance,
                 BigDecimal ocrTax, BigDecimal ocrConfidence) {
    }

    record Result(TaxValidationStatus status, List<String> reasons) {
    }

    Result validate(Input input);

    /** At submission: unresolved exceptions (MISMATCH, CONFIGURATION_MISSING, an OVERRIDE warning) go to Finance. */
    boolean needsFinanceReview(TaxValidationStatus status, List<String> reasons);
}
