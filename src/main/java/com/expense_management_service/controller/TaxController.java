package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.TaxCalculationRequest;
import com.expense_management_service.dto.response.ApplicableTaxCodesResponse;
import com.expense_management_service.dto.response.LineTaxComponentResponse;
import com.expense_management_service.dto.response.TaxCalculationResponse;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.TaxCode;
import com.expense_management_service.entity.TaxCodeComponent;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.service.TaxCalculationService;
import com.expense_management_service.service.TaxValidationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Collectors;

/** Tax preview for the expense forms - the same calculation line saves snapshot. Any signed-in user. */
@RestController
@RequiredArgsConstructor
public class TaxController {

    private final TaxCalculationService taxCalculationService;
    private final TaxValidationService taxValidationService;
    private final CurrencyRepository currencyRepository;

    @PostMapping("/xms/tax/calculate")
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public ApiResponse<TaxCalculationResponse> calculate(@Valid @RequestBody TaxCalculationRequest request) {
        Currency currency = currencyRepository.findById(request.currencyId())
                .orElseThrow(() -> new ResourceNotFoundException("Currency not found with id: " + request.currencyId()));
        int scale = currency.getDecimalPlaces() != null ? currency.getDecimalPlaces() : 2;
        TaxCalculationService.Result r = taxCalculationService.calculate(new TaxCalculationService.Input(
                request.amount(), scale, request.expenseDate(), request.categoryId(), request.taxCodeId(),
                request.enteredTax(), null));
        TaxCode code = r.taxCode();
        TaxValidationService.Result validation = taxValidationService.validate(new TaxValidationService.Input(
                code != null, r.taxAmount(), r.calculatedTax(), r.tolerance(), request.ocrTaxAmount(), request.ocrTaxConfidence()));
        BigDecimal itc = code != null && code.getItcRecoverablePercent() != null ? code.getItcRecoverablePercent() : BigDecimal.ZERO;
        return ApiResponse.success(new TaxCalculationResponse(
                code != null ? code.getTaxCodeId() : null,
                code != null ? code.getTaxCode() : null,
                code != null ? code.getTaxName() : null,
                code != null ? code.getTaxType().name() : null,
                code != null ? code.getRatePercent() : null,
                r.treatment().name(),
                r.grossAmount(),
                r.taxableAmount(),
                r.taxAmount(),
                r.calculatedTax(),
                r.difference(),
                r.tolerance(),
                r.isOverrideBeyondTolerance(),
                r.source().name(),
                validation.status().name(),
                validation.reasons(),
                itc,
                r.taxAmount().multiply(itc).divide(BigDecimal.valueOf(100), scale, RoundingMode.HALF_UP),
                r.components().stream()
                        .map(c -> new LineTaxComponentResponse(c.code().name(), c.label(), c.ratePercent(), c.amount(), null, c.source().name()))
                        .toList()));
    }

    @GetMapping("/xms/tax-codes/applicable")
    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public ApiResponse<ApplicableTaxCodesResponse> applicable(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate onDate = date != null ? date : LocalDate.now();
        TaxCode defaultCode = taxCalculationService.resolveTaxCode(categoryId, null, onDate).orElse(null);
        return ApiResponse.success(new ApplicableTaxCodesResponse(
                defaultCode != null ? defaultCode.getTaxCodeId() : null,
                defaultCode != null ? defaultCode.getTaxCode() : null,
                taxCalculationService.applicableCodes(onDate).stream()
                        .map(c -> new ApplicableTaxCodesResponse.Option(c.getTaxCodeId(), c.getTaxCode(), c.getTaxName(),
                                c.getTaxType().name(), c.getRatePercent(), summary(c)))
                        .toList()));
    }

    private static String summary(TaxCode code) {
        return code.getComponents().stream()
                .sorted(Comparator.comparing(TaxCodeComponent::getSequence))
                .map(TaxCodeComponent::getLabel)
                .collect(Collectors.joining(" + "));
    }
}
