package com.expense_management_service.service.impl;

import com.expense_management_service.entity.SystemConfiguration;
import com.expense_management_service.enums.TaxValidationStatus;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.service.TaxCalculationService;
import com.expense_management_service.service.TaxValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaxValidationServiceImpl implements TaxValidationService {

    static final String OCR_TOLERANCE_ABSOLUTE_KEY = "TAX_OCR_TOLERANCE_ABSOLUTE";
    static final String OCR_TOLERANCE_PERCENT_KEY = "TAX_OCR_TOLERANCE_PERCENT";
    static final String OCR_MIN_CONFIDENCE_KEY = "TAX_OCR_MIN_CONFIDENCE";
    private static final BigDecimal DEFAULT_OCR_TOLERANCE_ABSOLUTE = BigDecimal.ONE;
    private static final BigDecimal DEFAULT_OCR_TOLERANCE_PERCENT = BigDecimal.ONE;
    private static final BigDecimal DEFAULT_OCR_MIN_CONFIDENCE = BigDecimal.valueOf(80);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final SystemConfigurationRepository systemConfigurationRepository;

    @Override
    public Result validate(Input in) {
        BigDecimal tax = in.taxAmount() != null ? in.taxAmount() : BigDecimal.ZERO;
        List<String> reasons = new ArrayList<>();

        boolean overrideBeyondRounding = in.hasTaxCode() && in.calculatedTax() != null
                && tax.subtract(in.calculatedTax()).abs().compareTo(in.roundingTolerance() != null ? in.roundingTolerance() : BigDecimal.ZERO) > 0;
        if (!in.hasTaxCode() && tax.signum() > 0) {
            reasons.add(TaxCalculationService.REASON_NO_TAX_CODE);
        }
        if (overrideBeyondRounding) {
            reasons.add(TaxCalculationService.REASON_OVERRIDE);
        }

        boolean ocrCompared = false;
        boolean ocrMismatch = false;
        if (in.ocrTax() == null) {
            reasons.add(REASON_OCR_UNAVAILABLE);
        } else if (in.ocrConfidence() != null
                && in.ocrConfidence().multiply(HUNDRED).compareTo(config(OCR_MIN_CONFIDENCE_KEY, DEFAULT_OCR_MIN_CONFIDENCE)) < 0) {
            reasons.add(REASON_LOW_OCR_CONFIDENCE);
        } else {
            ocrCompared = true;
            BigDecimal percentTolerance = tax.max(in.ocrTax())
                    .multiply(config(OCR_TOLERANCE_PERCENT_KEY, DEFAULT_OCR_TOLERANCE_PERCENT))
                    .divide(HUNDRED, 4, RoundingMode.HALF_UP);
            BigDecimal tolerance = config(OCR_TOLERANCE_ABSOLUTE_KEY, DEFAULT_OCR_TOLERANCE_ABSOLUTE).max(percentTolerance);
            ocrMismatch = in.ocrTax().subtract(tax).abs().compareTo(tolerance) > 0;
            if (ocrMismatch) {
                reasons.add(REASON_OCR_MISMATCH);
            } else if (overrideBeyondRounding) {
                // Case C: the receipt agrees with what was entered, the configuration does not.
                reasons.add(REASON_CONFIG_MISMATCH);
            }
        }

        TaxValidationStatus status;
        if (ocrMismatch) {
            status = TaxValidationStatus.MISMATCH;
        } else if (!in.hasTaxCode()) {
            status = tax.signum() > 0 ? TaxValidationStatus.CONFIGURATION_MISSING : TaxValidationStatus.NOT_APPLICABLE;
        } else if (overrideBeyondRounding) {
            status = TaxValidationStatus.WARNING;
        } else if (ocrCompared) {
            status = TaxValidationStatus.MATCHED;
        } else {
            status = TaxValidationStatus.CALCULATED;
        }
        return new Result(status, List.copyOf(reasons));
    }

    @Override
    public boolean needsFinanceReview(TaxValidationStatus status, List<String> reasons) {
        return status == TaxValidationStatus.MISMATCH
                || status == TaxValidationStatus.CONFIGURATION_MISSING
                || (status == TaxValidationStatus.WARNING && reasons.contains(TaxCalculationService.REASON_OVERRIDE));
    }

    private BigDecimal config(String key, BigDecimal fallback) {
        return systemConfigurationRepository.findByConfigKey(key)
                .map(SystemConfiguration::getConfigValue)
                .map(TaxValidationServiceImpl::parseOrNull)
                .filter(v -> v.signum() >= 0)
                .orElse(fallback);
    }

    private static BigDecimal parseOrNull(String value) {
        try {
            return value == null ? null : new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
