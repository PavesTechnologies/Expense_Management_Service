package com.expense_management_service.service.impl;

import com.expense_management_service.entity.SystemConfiguration;
import com.expense_management_service.enums.TaxValidationStatus;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.service.TaxValidationService.Input;
import com.expense_management_service.service.TaxValidationService.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaxValidationServiceImplTest {

    @Mock
    private SystemConfigurationRepository systemConfigurationRepository;

    private TaxValidationServiceImpl service;
    private static final BigDecimal ROUNDING = new BigDecimal("0.02");

    @BeforeEach
    void setUp() {
        service = new TaxValidationServiceImpl(systemConfigurationRepository);
        lenient().when(systemConfigurationRepository.findByConfigKey(any())).thenReturn(Optional.empty());
    }

    private static BigDecimal d(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    private Result validate(boolean hasCode, String tax, String calculated, String ocr, String confidence) {
        return service.validate(new Input(hasCode, d(tax), d(calculated), ROUNDING, d(ocr), d(confidence)));
    }

    @Test
    void caseA_ocrEnteredAndCalculatedAgree_isMatched() {
        Result r = validate(true, "1800.00", "1800.00", "1800.40", "0.97");

        assertThat(r.status()).isEqualTo(TaxValidationStatus.MATCHED);
        assertThat(r.reasons()).isEmpty();
    }

    @Test
    void caseB_ocrDisagreesBeyondTolerance_isMismatch() {
        Result r = validate(true, "1800.00", "1800.00", "1620.00", "0.97");

        assertThat(r.status()).isEqualTo(TaxValidationStatus.MISMATCH);
        assertThat(r.reasons()).containsExactly("OCR_MISMATCH");
    }

    @Test
    void caseC_receiptAgreesWithEntered_notTheCode_isAConfigurationMismatchWarning() {
        Result r = validate(true, "1620.00", "1800.00", "1620.00", "0.97");

        assertThat(r.status()).isEqualTo(TaxValidationStatus.WARNING);
        assertThat(r.reasons()).containsExactly("OVERRIDE", "CONFIG_MISMATCH");
    }

    @Test
    void caseD_overrideWithoutOcr_isAnOverrideWarning() {
        Result r = validate(true, "1620.00", "1800.00", null, null);

        assertThat(r.status()).isEqualTo(TaxValidationStatus.WARNING);
        assertThat(r.reasons()).containsExactly("OVERRIDE", "OCR_UNAVAILABLE");
    }

    @Test
    void noOcr_calculated_staysCalculated() {
        Result r = validate(true, "1800.00", "1800.00", null, null);

        assertThat(r.status()).isEqualTo(TaxValidationStatus.CALCULATED);
        assertThat(r.reasons()).containsExactly("OCR_UNAVAILABLE");
    }

    @Test
    void lowConfidenceOcr_isIgnoredForComparison() {
        Result r = validate(true, "1800.00", "1800.00", "18.00", "0.62");

        assertThat(r.status()).isEqualTo(TaxValidationStatus.CALCULATED);
        assertThat(r.reasons()).containsExactly("LOW_OCR_CONFIDENCE");
    }

    @Test
    void multiItemRounding_withinThePercentTolerance_stillMatches() {
        // 1% of 5,000 = 50 > the 1.00 absolute tolerance, so a 30.00 drift on a large bill is fine.
        assertThat(validate(true, "5000.00", "5000.00", "5030.00", "0.9").status()).isEqualTo(TaxValidationStatus.MATCHED);
        // ...but a small bill only gets the absolute 1.00.
        assertThat(validate(true, "18.00", "18.00", "19.50", "0.9").status()).isEqualTo(TaxValidationStatus.MISMATCH);
    }

    @Test
    void tolerances_comeFromConfiguration() {
        when(systemConfigurationRepository.findByConfigKey("TAX_OCR_TOLERANCE_ABSOLUTE"))
                .thenReturn(Optional.of(SystemConfiguration.builder().configValue("5").build()));

        assertThat(validate(true, "18.00", "18.00", "21.00", "0.9").status()).isEqualTo(TaxValidationStatus.MATCHED);
    }

    @Test
    void noTaxCode_statusDependsOnWhetherTaxWasEntered() {
        assertThat(validate(false, "0", null, null, null).status()).isEqualTo(TaxValidationStatus.NOT_APPLICABLE);
        Result entered = validate(false, "45.00", null, null, null);
        assertThat(entered.status()).isEqualTo(TaxValidationStatus.CONFIGURATION_MISSING);
        assertThat(entered.reasons()).contains("NO_TAX_CODE");
        // Receipt shows tax the employee left out: a mismatch even without a code.
        assertThat(validate(false, "0", null, "45.00", "0.9").status()).isEqualTo(TaxValidationStatus.MISMATCH);
    }

    @Test
    void needsFinanceReview_forUnresolvedExceptionsOnly() {
        assertThat(service.needsFinanceReview(TaxValidationStatus.MISMATCH, List.of("OCR_MISMATCH"))).isTrue();
        assertThat(service.needsFinanceReview(TaxValidationStatus.CONFIGURATION_MISSING, List.of("NO_TAX_CODE"))).isTrue();
        assertThat(service.needsFinanceReview(TaxValidationStatus.WARNING, List.of("OVERRIDE"))).isTrue();
        assertThat(service.needsFinanceReview(TaxValidationStatus.MATCHED, List.of())).isFalse();
        assertThat(service.needsFinanceReview(TaxValidationStatus.CALCULATED, List.of("OCR_UNAVAILABLE"))).isFalse();
    }
}
