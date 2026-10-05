package com.expense_management_service.service.impl;

import com.expense_management_service.entity.SystemConfiguration;
import com.expense_management_service.entity.TaxCode;
import com.expense_management_service.entity.TaxCodeComponent;
import com.expense_management_service.enums.TaxComponentCode;
import com.expense_management_service.enums.TaxSource;
import com.expense_management_service.enums.TaxTreatment;
import com.expense_management_service.enums.TaxType;
import com.expense_management_service.enums.TaxValidationStatus;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.repository.TaxCodeRepository;
import com.expense_management_service.service.ExpenseCategoryTaxMappingService;
import com.expense_management_service.service.TaxCalculationService;
import com.expense_management_service.service.TaxCalculationService.Input;
import com.expense_management_service.service.TaxCalculationService.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaxCalculationServiceImplTest {

    @Mock
    private TaxCodeRepository taxCodeRepository;
    @Mock
    private ExpenseCategoryTaxMappingService mappingService;
    @Mock
    private SystemConfigurationRepository systemConfigurationRepository;

    private TaxCalculationServiceImpl service;
    private final UUID categoryId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 9, 1);

    @BeforeEach
    void setUp() {
        service = new TaxCalculationServiceImpl(taxCodeRepository, mappingService, systemConfigurationRepository);
        lenient().when(systemConfigurationRepository.findByConfigKey(any())).thenReturn(Optional.empty());
    }

    private static TaxCode code(String name, TaxType type, String... rates) {
        TaxCode code = TaxCode.builder().taxCodeId(UUID.randomUUID()).taxCode(name).taxName(name).taxType(type)
                .status("ACTIVE").effectiveFrom(LocalDate.of(2017, 7, 1)).itcRecoverablePercent(BigDecimal.ZERO)
                .components(new ArrayList<>()).build();
        TaxComponentCode[] codes = type == TaxType.IGST ? new TaxComponentCode[]{TaxComponentCode.IGST}
                : new TaxComponentCode[]{TaxComponentCode.CGST, TaxComponentCode.SGST, TaxComponentCode.CESS};
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < rates.length; i++) {
            BigDecimal r = new BigDecimal(rates[i]);
            total = total.add(r);
            code.getComponents().add(TaxCodeComponent.builder().taxCode(code).componentCode(codes[i])
                    .label(codes[i] + " " + rates[i] + "%").ratePercent(r).sequence(i + 1).build());
        }
        code.setRatePercent(total);
        return code;
    }

    private Result calc(TaxCode mapped, String amount, String entered, int scale) {
        when(mappingService.resolveTaxCode(categoryId, date)).thenReturn(Optional.ofNullable(mapped));
        return service.calculate(new Input(new BigDecimal(amount), scale, date, categoryId, null,
                entered == null ? null : new BigDecimal(entered), TaxTreatment.INCLUSIVE));
    }

    @Test
    void inclusive_gst18_workedExample() {
        Result r = calc(code("GST18", TaxType.CGST_SGST, "9", "9"), "11800.00", null, 2);

        assertThat(r.taxableAmount()).isEqualByComparingTo("10000.00");
        assertThat(r.taxAmount()).isEqualByComparingTo("1800.00");
        assertThat(r.components()).extracting(TaxCalculationService.ComponentAmount::amount)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("900.00"), new BigDecimal("900.00"));
        assertThat(r.source()).isEqualTo(TaxSource.CALCULATED);
        assertThat(r.status()).isEqualTo(TaxValidationStatus.CALCULATED);
    }

    @Test
    void inclusive_gst18_oddAmount_lastComponentAbsorbsRounding() {
        Result r = calc(code("GST18", TaxType.CGST_SGST, "9", "9"), "1000.00", null, 2);

        assertThat(r.taxableAmount()).isEqualByComparingTo("847.46");
        assertThat(r.taxAmount()).isEqualByComparingTo("152.54");
        assertThat(r.components().get(0).amount()).isEqualByComparingTo("76.27");
        assertThat(r.components().get(1).amount()).isEqualByComparingTo("76.27");
    }

    @Test
    void zeroDecimalCurrency_roundsToWholeUnits() {
        Result r = calc(code("IGST18", TaxType.IGST, "18"), "1001", null, 0);

        assertThat(r.taxableAmount()).isEqualByComparingTo("848");
        assertThat(r.taxAmount()).isEqualByComparingTo("153");
        assertThat(r.taxAmount().scale()).isZero();
    }

    @Test
    void property_taxablePlusComponentsAlwaysEqualsAmount() {
        Random random = new Random(42);
        List<TaxCode> codes = List.of(code("GST5", TaxType.CGST_SGST, "2.5", "2.5"), code("GST18", TaxType.CGST_SGST, "9", "9"),
                code("GST28C", TaxType.OTHER, "14", "14", "12"), code("IGST12", TaxType.IGST, "12"));
        for (int i = 0; i < 2000; i++) {
            TaxCode c = codes.get(i % codes.size());
            int scale = i % 7 == 0 ? 0 : 2;
            BigDecimal amount = BigDecimal.valueOf(1 + random.nextInt(5_000_000)).movePointLeft(scale == 0 ? 0 : 2);
            when(mappingService.resolveTaxCode(categoryId, date)).thenReturn(Optional.of(c));
            Result r = service.calculate(new Input(amount, scale, date, categoryId, null, null, TaxTreatment.INCLUSIVE));
            BigDecimal sum = r.components().stream().map(TaxCalculationService.ComponentAmount::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).as("components of %s at %s", amount, c.getTaxCode()).isEqualByComparingTo(r.taxAmount());
            assertThat(r.taxableAmount().add(r.taxAmount())).as("%s at %s", amount, c.getTaxCode()).isEqualByComparingTo(amount);
            assertThat(r.components()).allSatisfy(comp -> assertThat(comp.amount().signum()).isGreaterThanOrEqualTo(0));
        }
    }

    @Test
    void enteredTaxWithinRoundingTolerance_keepsTheCalculation() {
        // 2 components x 1 paisa tolerance: 152.55 vs 152.54 is rounding, not an override.
        Result r = calc(code("GST18", TaxType.CGST_SGST, "9", "9"), "1000.00", "152.55", 2);

        assertThat(r.taxAmount()).isEqualByComparingTo("152.54");
        assertThat(r.tolerance()).isEqualByComparingTo("0.02");
        assertThat(r.source()).isEqualTo(TaxSource.CALCULATED);
        assertThat(r.isOverrideBeyondTolerance()).isFalse();
    }

    @Test
    void enteredTaxBeyondTolerance_isAnOverride_splitAcrossComponentsByRate() {
        Result r = calc(code("GST18", TaxType.CGST_SGST, "9", "9"), "11800.00", "1620.00", 2);

        assertThat(r.taxAmount()).isEqualByComparingTo("1620.00");
        assertThat(r.calculatedTax()).isEqualByComparingTo("1800.00");
        assertThat(r.difference()).isEqualByComparingTo("-180.00");
        assertThat(r.taxableAmount()).isEqualByComparingTo("10180.00");
        assertThat(r.source()).isEqualTo(TaxSource.EMPLOYEE_OVERRIDE);
        assertThat(r.status()).isEqualTo(TaxValidationStatus.WARNING);
        assertThat(r.reasons()).containsExactly(TaxCalculationService.REASON_OVERRIDE);
        assertThat(r.components()).extracting(TaxCalculationService.ComponentAmount::amount)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("810.00"), new BigDecimal("810.00"));
    }

    @Test
    void noTaxCode_noTax_isNotApplicable() {
        Result r = calc(null, "500.00", null, 2);

        assertThat(r.taxAmount()).isEqualByComparingTo("0");
        assertThat(r.status()).isEqualTo(TaxValidationStatus.NOT_APPLICABLE);
        assertThat(r.source()).isEqualTo(TaxSource.NONE);
    }

    @Test
    void noTaxCode_enteredTax_isConfigurationMissing() {
        Result r = calc(null, "500.00", "45.00", 2);

        assertThat(r.taxAmount()).isEqualByComparingTo("45.00");
        assertThat(r.taxableAmount()).isEqualByComparingTo("455.00");
        assertThat(r.status()).isEqualTo(TaxValidationStatus.CONFIGURATION_MISSING);
        assertThat(r.components()).isEmpty();
    }

    @Test
    void exemptCode_hasNoTax() {
        TaxCode exempt = TaxCode.builder().taxCodeId(UUID.randomUUID()).taxCode("EXEMPT").taxType(TaxType.EXEMPT)
                .ratePercent(BigDecimal.ZERO).status("ACTIVE").effectiveFrom(LocalDate.of(2017, 7, 1)).components(new ArrayList<>()).build();
        Result r = calc(exempt, "300.00", null, 2);

        assertThat(r.taxAmount()).isEqualByComparingTo("0");
        assertThat(r.taxableAmount()).isEqualByComparingTo("300.00");
        assertThat(r.status()).isEqualTo(TaxValidationStatus.CALCULATED);
    }

    @Test
    void explicitCode_overridesTheCategoryMapping_butMustBeActiveOnTheDate() {
        TaxCode igst = code("IGST18", TaxType.IGST, "18");
        when(taxCodeRepository.findById(igst.getTaxCodeId())).thenReturn(Optional.of(igst));

        Result r = service.calculate(new Input(new BigDecimal("1180.00"), 2, date, categoryId, igst.getTaxCodeId(), null, null));
        assertThat(r.taxCode()).isSameAs(igst);
        assertThat(r.taxAmount()).isEqualByComparingTo("180.00");

        igst.setStatus("INACTIVE");
        assertThatThrownBy(() -> service.calculate(new Input(new BigDecimal("1180.00"), 2, date, categoryId, igst.getTaxCodeId(), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not active");
    }

    @Test
    void taxAboveAmount_isRejected() {
        assertThatThrownBy(() -> calc(null, "100.00", "150.00", 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceed");
    }

    @Test
    void roundingTolerance_isConfigurablePerComponent() {
        when(systemConfigurationRepository.findByConfigKey("TAX_ROUNDING_TOLERANCE_MINOR_UNITS"))
                .thenReturn(Optional.of(SystemConfiguration.builder().configValue("5").build()));

        assertThat(service.roundingTolerance(2, 2)).isEqualByComparingTo("0.10");
        assertThat(service.roundingTolerance(1, 0)).isEqualByComparingTo("5");
    }
}
