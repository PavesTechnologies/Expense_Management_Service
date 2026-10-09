package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.entity.SystemConfiguration;
import com.expense_management_service.entity.TaxCode;
import com.expense_management_service.entity.TaxCodeComponent;
import com.expense_management_service.enums.TaxComponentSource;
import com.expense_management_service.enums.TaxSource;
import com.expense_management_service.enums.TaxTreatment;
import com.expense_management_service.enums.TaxValidationStatus;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.repository.TaxCodeRepository;
import com.expense_management_service.service.ExpenseCategoryTaxMappingService;
import com.expense_management_service.service.TaxCalculationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaxCalculationServiceImpl implements TaxCalculationService {

    static final String ROUNDING_TOLERANCE_KEY = "TAX_ROUNDING_TOLERANCE_MINOR_UNITS";
    private static final BigDecimal DEFAULT_ROUNDING_TOLERANCE_MINOR_UNITS = BigDecimal.ONE;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String COUNTRY = "IN";

    private final TaxCodeRepository taxCodeRepository;
    private final ExpenseCategoryTaxMappingService mappingService;
    private final SystemConfigurationRepository systemConfigurationRepository;

    @Override
    public Result calculate(Input input) {
        int scale = input.scale();
        TaxTreatment treatment = input.treatment() != null ? input.treatment() : TaxTreatment.INCLUSIVE;
        BigDecimal amount = input.amount().setScale(scale, RoundingMode.HALF_UP);
        BigDecimal entered = input.enteredTax() == null ? null : input.enteredTax().setScale(scale, RoundingMode.HALF_UP);
        if (entered != null && entered.signum() < 0) {
            throw new IllegalArgumentException("Tax cannot be negative");
        }
        TaxCode code = resolveTaxCode(input.categoryId(), input.taxCodeId(), input.expenseDate()).orElse(null);

        if (code == null) {
            BigDecimal tax = entered == null ? BigDecimal.ZERO.setScale(scale) : entered;
            assertNotAboveAmount(tax, amount, treatment);
            boolean taxed = tax.signum() > 0;
            return new Result(null, treatment, gross(amount, tax, treatment), taxable(amount, tax, treatment), null, tax,
                    BigDecimal.ZERO.setScale(scale), taxed ? TaxSource.EMPLOYEE_OVERRIDE : TaxSource.NONE,
                    taxed ? TaxValidationStatus.CONFIGURATION_MISSING : TaxValidationStatus.NOT_APPLICABLE,
                    taxed ? List.of(REASON_NO_TAX_CODE) : List.of(), List.of());
        }

        List<TaxCodeComponent> parts = code.getComponents().stream()
                .sorted(Comparator.comparing(TaxCodeComponent::getSequence))
                .toList();
        BigDecimal totalRate = parts.stream().map(TaxCodeComponent::getRatePercent).reduce(BigDecimal.ZERO, BigDecimal::add);

        // Calculated breakdown: taxable first, then per-component tax, last component absorbs rounding.
        BigDecimal calcTaxable;
        BigDecimal calcTax;
        if (treatment == TaxTreatment.INCLUSIVE) {
            calcTaxable = totalRate.signum() == 0 ? amount
                    : amount.multiply(HUNDRED).divide(HUNDRED.add(totalRate), scale, RoundingMode.HALF_UP);
            calcTax = amount.subtract(calcTaxable);
        } else {
            calcTaxable = amount;
            calcTax = parts.stream()
                    .map(p -> amount.multiply(p.getRatePercent()).divide(HUNDRED, scale, RoundingMode.HALF_UP))
                    .reduce(BigDecimal.ZERO.setScale(scale), BigDecimal::add);
        }
        List<BigDecimal> calcShares = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO.setScale(scale);
        for (int i = 0; i < parts.size(); i++) {
            BigDecimal share = i == parts.size() - 1 ? calcTax.subtract(running)
                    : calcTaxable.multiply(parts.get(i).getRatePercent()).divide(HUNDRED, scale, RoundingMode.HALF_UP);
            calcShares.add(share);
            running = running.add(share);
        }

        BigDecimal tolerance = roundingTolerance(parts.size(), scale);
        boolean override = entered != null && entered.subtract(calcTax).abs().compareTo(tolerance) > 0;
        BigDecimal tax = override ? entered : calcTax;
        assertNotAboveAmount(tax, amount, treatment);

        List<BigDecimal> shares = override
                ? TaxCalculationService.splitProportionally(tax, parts.stream().map(TaxCodeComponent::getRatePercent).toList(), scale)
                : calcShares;
        TaxComponentSource componentSource = override ? TaxComponentSource.ENTERED : TaxComponentSource.CALCULATED;
        List<ComponentAmount> components = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            TaxCodeComponent p = parts.get(i);
            components.add(new ComponentAmount(p.getComponentCode(), p.getLabel(), p.getRatePercent(), shares.get(i), componentSource));
        }

        return new Result(code, treatment, gross(amount, tax, treatment), taxable(amount, tax, treatment), calcTax, tax, tolerance,
                override ? TaxSource.EMPLOYEE_OVERRIDE : TaxSource.CALCULATED,
                override ? TaxValidationStatus.WARNING : TaxValidationStatus.CALCULATED,
                override ? List.of(REASON_OVERRIDE) : List.of(), components);
    }

    @Override
    public Optional<TaxCode> resolveTaxCode(UUID categoryId, UUID taxCodeId, LocalDate date) {
        if (taxCodeId != null) {
            TaxCode code = taxCodeRepository.findById(taxCodeId)
                    .orElseThrow(() -> new ResourceNotFoundException("TaxCode not found with id: " + taxCodeId));
            // VR-TAX-05 / VR-TAX-11: a selected code must be active and in effect on the expense date.
            if (!code.isApplicableOn(date)) {
                throw new IllegalArgumentException("Tax code " + code.getTaxCode() + " is not active on " + date
                        + " - choose another code for this expense.");
            }
            return Optional.of(code);
        }
        return categoryId == null ? Optional.empty() : mappingService.resolveTaxCode(categoryId, date);
    }

    @Override
    public List<TaxCode> applicableCodes(LocalDate date) {
        return taxCodeRepository.findByStatusIgnoreCaseOrderByRatePercentAsc(STATUS_ACTIVE).stream()
                .filter(code -> COUNTRY.equalsIgnoreCase(code.getCountryCode()))
                .filter(code -> code.isApplicableOn(date))
                .toList();
    }

    @Override
    public BigDecimal roundingTolerance(int componentCount, int scale) {
        BigDecimal minorUnits = systemConfigurationRepository.findByConfigKey(ROUNDING_TOLERANCE_KEY)
                .map(SystemConfiguration::getConfigValue)
                .map(TaxCalculationServiceImpl::parseOrNull)
                .filter(v -> v.signum() >= 0)
                .orElse(DEFAULT_ROUNDING_TOLERANCE_MINOR_UNITS);
        return minorUnits.multiply(BigDecimal.valueOf(Math.max(1, componentCount))).movePointLeft(scale).setScale(scale, RoundingMode.HALF_UP);
    }

    private static BigDecimal parseOrNull(String value) {
        try {
            return value == null ? null : new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void assertNotAboveAmount(BigDecimal tax, BigDecimal amount, TaxTreatment treatment) {
        if (treatment == TaxTreatment.INCLUSIVE && tax.compareTo(amount) > 0) {
            throw new IllegalArgumentException("Tax cannot exceed the total amount");
        }
    }

    private static BigDecimal gross(BigDecimal amount, BigDecimal tax, TaxTreatment treatment) {
        return treatment == TaxTreatment.INCLUSIVE ? amount : amount.add(tax);
    }

    private static BigDecimal taxable(BigDecimal amount, BigDecimal tax, TaxTreatment treatment) {
        return treatment == TaxTreatment.INCLUSIVE ? amount.subtract(tax) : amount;
    }
}
