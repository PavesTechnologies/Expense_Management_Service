package com.expense_management_service.service;

import com.expense_management_service.entity.TaxCode;
import com.expense_management_service.enums.TaxComponentCode;
import com.expense_management_service.enums.TaxComponentSource;
import com.expense_management_service.enums.TaxSource;
import com.expense_management_service.enums.TaxTreatment;
import com.expense_management_service.enums.TaxValidationStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The one place expense tax is calculated (BR-TAX-001): line saves snapshot its result and the
 * calculate API previews it, so the browser never has its own formula.
 * <p>
 * Inclusive (the default - receipts show what was paid): taxable = round(amount x 100 / (100 + R)),
 * tax = amount - taxable, and each component but the last is round(taxable x r / 100) with the last
 * absorbing the remainder, so taxable + components = amount exactly. Rounding is HALF_UP to the
 * currency's minor units.
 */
public interface TaxCalculationService {

    /** Reason code: entered tax differs from the configured calculation beyond the rounding tolerance. */
    String REASON_OVERRIDE = "OVERRIDE";
    /** Reason code: tax entered for a category with no applicable tax code. */
    String REASON_NO_TAX_CODE = "NO_TAX_CODE";

    /**
     * @param amount     the line amount - tax-inclusive gross for INCLUSIVE, pre-tax for EXCLUSIVE
     * @param scale      the transaction currency's minor units (INR 2, JPY 0)
     * @param taxCodeId  explicit selection; null = the category's mapping on {@code expenseDate}
     * @param enteredTax what the employee (or OCR) says the tax is; null = use the calculation
     */
    record Input(BigDecimal amount, int scale, LocalDate expenseDate, UUID categoryId, UUID taxCodeId,
                 BigDecimal enteredTax, TaxTreatment treatment) {
    }

    record ComponentAmount(TaxComponentCode code, String label, BigDecimal ratePercent, BigDecimal amount,
                           TaxComponentSource source) {
    }

    /**
     * @param calculatedTax what the tax code says (null when no code applies)
     * @param taxAmount     the final tax: the calculation, or the entered value when it differs beyond {@code tolerance}
     * @param grossAmount   what was paid (= amount for INCLUSIVE)
     */
    record Result(TaxCode taxCode, TaxTreatment treatment, BigDecimal grossAmount, BigDecimal taxableAmount,
                  BigDecimal calculatedTax, BigDecimal taxAmount, BigDecimal tolerance, TaxSource source,
                  TaxValidationStatus status, List<String> reasons, List<ComponentAmount> components) {

        public boolean isOverrideBeyondTolerance() {
            return reasons.contains(REASON_OVERRIDE);
        }

        /** entered - calculated (null without a code or an entered value). */
        public BigDecimal difference() {
            return calculatedTax == null ? null : taxAmount.subtract(calculatedTax);
        }
    }

    Result calculate(Input input);

    /** BR-TAX-003: the explicit code (must be active and in effect on the date), else the category mapping, else none. */
    Optional<TaxCode> resolveTaxCode(UUID categoryId, UUID taxCodeId, LocalDate date);

    /** Active India codes in effect on {@code date}, lowest rate first - what an employee may pick. */
    List<TaxCode> applicableCodes(LocalDate date);

    /** Largest |entered - calculated| still treated as rounding: TAX_ROUNDING_TOLERANCE_MINOR_UNITS per component. */
    BigDecimal roundingTolerance(int componentCount, int scale);

    /**
     * Splits {@code total} across {@code weights}: each share but the last is round(total x w / sum),
     * the last absorbs the remainder so the shares always sum to {@code total}.
     */
    static List<BigDecimal> splitProportionally(BigDecimal total, List<BigDecimal> weights, int scale) {
        BigDecimal sum = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<BigDecimal> shares = new java.util.ArrayList<>(weights.size());
        BigDecimal running = BigDecimal.ZERO.setScale(scale);
        for (int i = 0; i < weights.size(); i++) {
            BigDecimal share;
            if (i == weights.size() - 1) {
                share = total.subtract(running).setScale(scale, java.math.RoundingMode.HALF_UP);
            } else if (sum.signum() == 0) {
                share = BigDecimal.ZERO.setScale(scale);
            } else {
                share = total.multiply(weights.get(i)).divide(sum, scale, java.math.RoundingMode.HALF_UP);
            }
            shares.add(share);
            running = running.add(share);
        }
        return shares;
    }
}
