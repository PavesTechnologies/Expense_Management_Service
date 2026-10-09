package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseLineTaxComponent;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.TaxCode;
import com.expense_management_service.enums.AuditSource;
import com.expense_management_service.enums.TaxSource;
import com.expense_management_service.enums.TaxTreatment;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.service.TaxAuditService;
import com.expense_management_service.service.TaxCalculationService;
import com.expense_management_service.service.TaxSnapshotService;
import com.expense_management_service.service.TaxValidationService;
import com.expense_management_service.enums.TaxValidationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class TaxSnapshotServiceImpl implements TaxSnapshotService {

    private static final String ENTITY = "ExpenseLineItem";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final TaxCalculationService taxCalculationService;
    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final TaxAuditService taxAuditService;
    private final TaxValidationService taxValidationService;
    private final CurrencyRepository currencyRepository;

    @Value("${exchange.rate.base-currency:INR}")
    private String baseCurrencyCode;

    @Override
    public void applySnapshot(ExpenseLineItem line, UUID requestedTaxCodeId, BigDecimal enteredTax, String overrideReason,
                              Map<String, Object> previous, int baseScale) {
        TaxCalculationService.Result result = calculate(line, requestedTaxCodeId, enteredTax);
        writeSnapshot(line, result, result.source() == TaxSource.EMPLOYEE_OVERRIDE && StringUtils.hasText(overrideReason)
                ? overrideReason.trim() : null, baseScale);
        // Editable again (new line, or a correction after an earlier submission): frozen anew at submit.
        line.setTaxSnapshotAt(null);

        // Draft edits are not audited (noise); changes while correcting a submitted report are.
        ExpenseReport report = line.getReport();
        if (previous != null && report != null && report.getSubmittedAt() != null) {
            Map<String, Object> now = describe(line);
            if (!Objects.equals(previous, now) && line.getLineItemId() != null) {
                line.setTaxRevisedAt(LocalDateTime.now());
                taxAuditService.record(ENTITY, line.getLineItemId(), "TAX_CHANGED_DURING_CORRECTION", previous, now,
                        AuditSource.EMPLOYEE, line.getTaxOverrideReason());
            }
        }
    }

    private TaxCalculationService.Result calculate(ExpenseLineItem line, UUID taxCodeId, BigDecimal enteredTax) {
        return taxCalculationService.calculate(new TaxCalculationService.Input(
                line.getAmount(), scaleOf(line), line.getExpenseDate(),
                line.getCategory() != null ? line.getCategory().getCategoryId() : null,
                taxCodeId, enteredTax, TaxTreatment.INCLUSIVE));
    }

    private static int scaleOf(ExpenseLineItem line) {
        return line.getCurrency() != null && line.getCurrency().getDecimalPlaces() != null ? line.getCurrency().getDecimalPlaces() : 2;
    }

    /** Code, final/calculated tax, components, ITC and base-currency values from a calculation result. */
    private void writeSnapshot(ExpenseLineItem line, TaxCalculationService.Result result, String overrideReason, int baseScale) {
        int scale = scaleOf(line);
        TaxCode code = result.taxCode();
        line.setTaxCodeId(code != null ? code.getTaxCodeId() : null);
        line.setTaxCode(code != null ? code.getTaxCode() : null);
        line.setTaxType(code != null ? code.getTaxType() : null);
        line.setTaxRatePercent(code != null ? code.getRatePercent() : null);
        line.setTaxTreatment(result.treatment());
        line.setCalculatedTaxAmount(result.calculatedTax());
        line.setTaxAmount(result.taxAmount());
        line.setNetAmount(line.getAmount().subtract(result.taxAmount()));
        line.setTaxSource(result.source());
        line.setTaxOverrideReason(overrideReason);
        applyValidation(line, result.tolerance());

        BigDecimal itcPercent = code != null && code.getItcRecoverablePercent() != null ? code.getItcRecoverablePercent() : BigDecimal.ZERO;
        line.setItcRecoverablePercent(itcPercent);
        line.setRecoverableTaxAmount(percentOf(result.taxAmount(), itcPercent, scale));

        // Base currency: tax converted with the line's own rate, net derived so the two add up to baseAmount.
        BigDecimal rate = line.getExchangeRate() != null ? line.getExchangeRate() : BigDecimal.ONE;
        BigDecimal baseAmount = line.getBaseAmount() != null ? line.getBaseAmount() : line.getAmount();
        BigDecimal baseTax = result.taxAmount().multiply(rate).setScale(baseScale, RoundingMode.HALF_UP);
        line.setBaseTaxAmount(baseTax);
        line.setBaseNetAmount(baseAmount.subtract(baseTax));
        line.setBaseRecoverableTaxAmount(percentOf(baseTax, itcPercent, baseScale));

        List<TaxCalculationService.ComponentAmount> components = result.components();
        List<BigDecimal> baseShares = TaxCalculationService.splitProportionally(baseTax,
                components.stream().map(TaxCalculationService.ComponentAmount::amount).toList(), baseScale);
        line.getTaxComponents().clear();
        for (int i = 0; i < components.size(); i++) {
            TaxCalculationService.ComponentAmount c = components.get(i);
            line.getTaxComponents().add(ExpenseLineTaxComponent.builder()
                    .lineItem(line)
                    .componentCode(c.code())
                    .label(c.label())
                    .ratePercent(c.ratePercent())
                    .taxAmount(c.amount())
                    .baseTaxAmount(baseShares.get(i))
                    .sequence(i + 1)
                    .source(c.source())
                    .build());
        }
    }

    @Override
    public void markFinanceVerified(ExpenseLineItem line) {
        if (line.getTaxValidationStatus() == TaxValidationStatus.FINANCE_ADJUSTED) {
            return;
        }
        Map<String, Object> before = describe(line);
        line.setTaxValidationStatus(TaxValidationStatus.FINANCE_VERIFIED);
        expenseLineItemRepository.save(line);
        taxAuditService.record(ENTITY, line.getLineItemId(), "TAX_FINANCE_VERIFIED", before, describe(line), AuditSource.FINANCE, null);
    }

    @Override
    public void financeAdjust(ExpenseLineItem line, UUID taxCodeId, BigDecimal taxAmount, BigDecimal itcRecoverablePercent, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new IllegalArgumentException("A reason is required to adjust tax.");
        }
        if (line.getReport() != null && line.getReport().getPaymentRoutingStatus() != null
                && "PAYMENT_COMPLETED".equals(line.getReport().getPaymentRoutingStatus().name())) {
            throw new IllegalArgumentException("Tax cannot change after payment has completed.");
        }
        if (taxAmount != null && taxAmount.compareTo(line.getAmount()) > 0) {
            throw new IllegalArgumentException("Tax cannot exceed the line amount; the gross amount itself needs a correction request.");
        }
        Map<String, Object> before = describe(line);
        UUID code = taxCodeId != null ? taxCodeId : line.getTaxCodeId();
        TaxCalculationService.Result result = calculate(line, code, taxAmount);
        writeSnapshot(line, result, line.getTaxOverrideReason(), baseScale());

        if (itcRecoverablePercent != null) {
            BigDecimal itc = itcRecoverablePercent.setScale(2, RoundingMode.HALF_UP);
            line.setItcRecoverablePercent(itc);
            line.setRecoverableTaxAmount(percentOf(line.getTaxAmount(), itc, scaleOf(line)));
            line.setBaseRecoverableTaxAmount(percentOf(line.getBaseTaxAmount(), itc, baseScale()));
        }
        line.setTaxSource(TaxSource.FINANCE_ADJUSTED);
        line.setTaxValidationStatus(TaxValidationStatus.FINANCE_ADJUSTED);
        line.setTaxSnapshotAt(LocalDateTime.now());
        expenseLineItemRepository.save(line);

        Map<String, Object> after = describe(line);
        after.put("itcRecoverablePercent", plain(line.getItcRecoverablePercent()));
        before.putIfAbsent("itcRecoverablePercent", null);
        taxAuditService.record(ENTITY, line.getLineItemId(), "TAX_FINANCE_ADJUSTED", before, after, AuditSource.FINANCE, reason.trim());
    }

    @Override
    public void recordFinanceVerificationReset(ExpenseLineItem line) {
        taxAuditService.record(ENTITY, line.getLineItemId(), "TAX_FINANCE_VERIFICATION_RESET", null, describe(line), AuditSource.SYSTEM,
                "Tax changed during correction");
    }

    private int baseScale() {
        return currencyRepository.findByCurrencyCodeIgnoreCase(baseCurrencyCode)
                .map(c -> c.getDecimalPlaces() != null ? c.getDecimalPlaces() : 2)
                .orElse(2);
    }

    @Override
    public void freezeForSubmission(ExpenseReport report) {
        List<ExpenseLineItem> lines = expenseLineItemRepository.findByReport_ReportId(report.getReportId());
        List<ExpenseLineItem> missingReason = lines.stream()
                .filter(this::isOverrideBeyondTolerance)
                .filter(l -> !StringUtils.hasText(l.getTaxOverrideReason()))
                .toList();
        if (!missingReason.isEmpty()) {
            String details = missingReason.stream()
                    .map(l -> (StringUtils.hasText(l.getMerchantName()) ? l.getMerchantName() : "Expense") + " on " + l.getExpenseDate()
                            + " (entered " + plain(l.getTaxAmount()) + ", " + l.getTaxCode() + " gives " + plain(l.getCalculatedTaxAmount()) + ")")
                    .collect(Collectors.joining("; "));
            throw new IllegalArgumentException("Add a reason for the tax that differs from its tax code before submitting: " + details);
        }

        LocalDateTime now = LocalDateTime.now();
        for (ExpenseLineItem line : lines) {
            line.setTaxSnapshotAt(now);
            if (line.getTaxValidationStatus() != null
                    && taxValidationService.needsFinanceReview(line.getTaxValidationStatus(), reasonsOf(line))) {
                Map<String, Object> before = describe(line);
                line.setTaxValidationStatus(TaxValidationStatus.REQUIRES_FINANCE_REVIEW);
                taxAuditService.record(ENTITY, line.getLineItemId(), "TAX_VALIDATION_STATUS_CHANGED", before, describe(line),
                        AuditSource.SYSTEM, null);
            }
            taxAuditService.record(ENTITY, line.getLineItemId(), "TAX_SNAPSHOT_FROZEN", null, describe(line), AuditSource.SYSTEM, null);
            if (isOverrideBeyondTolerance(line)) {
                taxAuditService.record(ENTITY, line.getLineItemId(), "TAX_OVERRIDE", null, describe(line), AuditSource.EMPLOYEE,
                        line.getTaxOverrideReason());
            }
        }
        expenseLineItemRepository.saveAll(lines);
    }

    @Override
    public void attachOcrEvidence(ExpenseLineItem line, BigDecimal ocrTax, BigDecimal ocrConfidence) {
        if (ocrTax == null) {
            return;
        }
        Map<String, Object> before = describe(line);
        line.setOcrTaxAmount(ocrTax);
        line.setOcrTaxConfidence(ocrConfidence);
        int scale = line.getCurrency() != null && line.getCurrency().getDecimalPlaces() != null ? line.getCurrency().getDecimalPlaces() : 2;
        applyValidation(line, taxCalculationService.roundingTolerance(Math.max(1, line.getTaxComponents().size()), scale));
        expenseLineItemRepository.save(line);
        Map<String, Object> after = describe(line);
        after.put("ocrTaxAmount", plain(ocrTax));
        taxAuditService.record(ENTITY, line.getLineItemId(), "OCR_TAX_ATTACHED", before, after, AuditSource.OCR, null);
    }

    /** Status and reasons from the line's tax, its code's calculation and the OCR evidence on it. */
    private void applyValidation(ExpenseLineItem line, BigDecimal roundingTolerance) {
        TaxValidationService.Result v = taxValidationService.validate(new TaxValidationService.Input(
                line.getTaxCodeId() != null, line.getTaxAmount(), line.getCalculatedTaxAmount(), roundingTolerance,
                line.getOcrTaxAmount(), line.getOcrTaxConfidence()));
        line.setTaxValidationStatus(v.status());
        line.setTaxValidationReasons(v.reasons().isEmpty() ? null : String.join(",", v.reasons()));
    }

    private static List<String> reasonsOf(ExpenseLineItem line) {
        return line.getTaxValidationReasons() == null ? List.of() : List.of(line.getTaxValidationReasons().split(","));
    }

    @Override
    public Map<String, Object> describe(ExpenseLineItem line) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("taxCode", line.getTaxCode());
        values.put("taxRatePercent", plain(line.getTaxRatePercent()));
        values.put("taxAmount", plain(line.getTaxAmount()));
        values.put("calculatedTaxAmount", plain(line.getCalculatedTaxAmount()));
        values.put("taxSource", line.getTaxSource() == null ? null : line.getTaxSource().name());
        values.put("components", line.getTaxComponents().stream()
                .map(c -> c.getComponentCode() + ":" + plain(c.getTaxAmount()))
                .collect(Collectors.joining(",")));
        values.put("validationStatus", line.getTaxValidationStatus() == null ? null : line.getTaxValidationStatus().name());
        return values;
    }

    private boolean isOverrideBeyondTolerance(ExpenseLineItem line) {
        return line.getTaxSource() == TaxSource.EMPLOYEE_OVERRIDE
                && reasonsOf(line).contains(TaxCalculationService.REASON_OVERRIDE);
    }

    private static BigDecimal percentOf(BigDecimal value, BigDecimal percent, int scale) {
        return value.multiply(percent).divide(HUNDRED, scale, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
