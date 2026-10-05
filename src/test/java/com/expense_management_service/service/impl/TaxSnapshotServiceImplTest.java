package com.expense_management_service.service.impl;

import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.TaxCode;
import com.expense_management_service.entity.TaxCodeComponent;
import com.expense_management_service.enums.AuditSource;
import com.expense_management_service.enums.TaxComponentCode;
import com.expense_management_service.enums.TaxSource;
import com.expense_management_service.enums.TaxType;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.repository.TaxCodeRepository;
import com.expense_management_service.service.ExpenseCategoryTaxMappingService;
import com.expense_management_service.service.TaxAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaxSnapshotServiceImplTest {

    @Mock
    private TaxCodeRepository taxCodeRepository;
    @Mock
    private ExpenseCategoryTaxMappingService mappingService;
    @Mock
    private SystemConfigurationRepository systemConfigurationRepository;
    @Mock
    private ExpenseLineItemRepository expenseLineItemRepository;
    @Mock
    private TaxAuditService taxAuditService;

    private TaxSnapshotServiceImpl service;
    private final UUID categoryId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 9, 1);
    private TaxCode gst18;

    @BeforeEach
    void setUp() {
        service = new TaxSnapshotServiceImpl(new TaxCalculationServiceImpl(taxCodeRepository, mappingService, systemConfigurationRepository),
                expenseLineItemRepository, taxAuditService, new TaxValidationServiceImpl(systemConfigurationRepository), org.mockito.Mockito.mock(com.expense_management_service.repository.CurrencyRepository.class));
        lenient().when(systemConfigurationRepository.findByConfigKey(any())).thenReturn(Optional.empty());
        gst18 = TaxCode.builder().taxCodeId(UUID.randomUUID()).taxCode("GST18").taxName("GST 18%").taxType(TaxType.CGST_SGST)
                .ratePercent(new BigDecimal("18.00")).itcRecoverablePercent(new BigDecimal("50.00")).status("ACTIVE")
                .effectiveFrom(LocalDate.of(2017, 7, 1)).components(new ArrayList<>()).build();
        gst18.getComponents().add(TaxCodeComponent.builder().taxCode(gst18).componentCode(TaxComponentCode.CGST).label("CGST 9%")
                .ratePercent(new BigDecimal("9.00")).sequence(1).build());
        gst18.getComponents().add(TaxCodeComponent.builder().taxCode(gst18).componentCode(TaxComponentCode.SGST).label("SGST 9%")
                .ratePercent(new BigDecimal("9.00")).sequence(2).build());
        lenient().when(mappingService.resolveTaxCode(categoryId, date)).thenReturn(Optional.of(gst18));
    }

    private ExpenseLineItem line(String amount, String exchangeRate, ExpenseReport report) {
        BigDecimal a = new BigDecimal(amount);
        BigDecimal rate = new BigDecimal(exchangeRate);
        return ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).report(report).expenseDate(date).merchantName("Hotel")
                .category(ExpenseCategory.builder().categoryId(categoryId).build())
                .currency(Currency.builder().currencyCode("USD").decimalPlaces(2).build())
                .amount(a).exchangeRate(rate).baseAmount(a.multiply(rate).setScale(4, java.math.RoundingMode.HALF_UP)).build();
    }

    @Test
    void applySnapshot_storesCodeComponentsItcAndBaseValues() {
        ExpenseLineItem line = line("118.00", "83.25", null);

        service.applySnapshot(line, null, null, null, null, 2);

        assertThat(line.getTaxCode()).isEqualTo("GST18");
        assertThat(line.getTaxAmount()).isEqualByComparingTo("18.00");
        assertThat(line.getNetAmount()).isEqualByComparingTo("100.00");
        assertThat(line.getTaxSource()).isEqualTo(TaxSource.CALCULATED);
        assertThat(line.getTaxValidationStatus()).isEqualTo(com.expense_management_service.enums.TaxValidationStatus.CALCULATED);
        assertThat(line.getRecoverableTaxAmount()).isEqualByComparingTo("9.00");
        assertThat(line.getBaseTaxAmount()).isEqualByComparingTo("1498.50");
        assertThat(line.getBaseNetAmount().add(line.getBaseTaxAmount())).isEqualByComparingTo(line.getBaseAmount());
        assertThat(line.getBaseRecoverableTaxAmount()).isEqualByComparingTo("749.25");
        assertThat(line.getTaxComponents()).hasSize(2);
        BigDecimal baseComponentSum = line.getTaxComponents().stream().map(c -> c.getBaseTaxAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(baseComponentSum).isEqualByComparingTo(line.getBaseTaxAmount());
    }

    @Test
    void applySnapshot_replacesComponentsOnResave() {
        ExpenseLineItem line = line("118.00", "1", null);
        service.applySnapshot(line, null, null, null, null, 2);
        service.applySnapshot(line, null, null, null, null, 2);

        assertThat(line.getTaxComponents()).hasSize(2);
    }

    @Test
    void applySnapshot_auditsTaxChanges_onlyWhenCorrectingASubmittedReport() {
        ExpenseReport draft = ExpenseReport.builder().reportId(UUID.randomUUID()).build();
        ExpenseLineItem draftLine = line("118.00", "1", draft);
        service.applySnapshot(draftLine, null, null, null, null, 2);
        service.applySnapshot(draftLine, null, new BigDecimal("10.00"), "Receipt shows 10", service.describe(draftLine), 2);
        verify(taxAuditService, never()).record(any(), any(), any(), any(), any(), any(), any());

        ExpenseReport corrected = ExpenseReport.builder().reportId(UUID.randomUUID()).submittedAt(LocalDateTime.now()).build();
        ExpenseLineItem correctedLine = line("118.00", "1", corrected);
        service.applySnapshot(correctedLine, null, null, null, null, 2);
        service.applySnapshot(correctedLine, null, new BigDecimal("10.00"), "Receipt shows 10", service.describe(correctedLine), 2);

        verify(taxAuditService).record(eq("ExpenseLineItem"), eq(correctedLine.getLineItemId()), eq("TAX_CHANGED_DURING_CORRECTION"),
                any(), any(), eq(AuditSource.EMPLOYEE), eq("Receipt shows 10"));
    }

    @Test
    void freezeForSubmission_rejectsAnOverrideWithoutAReason() {
        ExpenseReport report = ExpenseReport.builder().reportId(UUID.randomUUID()).build();
        ExpenseLineItem line = line("11800.00", "1", report);
        service.applySnapshot(line, null, new BigDecimal("1620.00"), null, null, 2);
        when(expenseLineItemRepository.findByReport_ReportId(report.getReportId())).thenReturn(List.of(line));

        assertThatThrownBy(() -> service.freezeForSubmission(report))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Add a reason")
                .hasMessageContaining("GST18 gives 1800");
        assertThat(line.getTaxSnapshotAt()).isNull();
    }

    @Test
    void freezeForSubmission_stampsAndAuditsEveryLine_includingExplainedOverrides() {
        ExpenseReport report = ExpenseReport.builder().reportId(UUID.randomUUID()).build();
        ExpenseLineItem calculated = line("1180.00", "1", report);
        service.applySnapshot(calculated, null, null, null, null, 2);
        ExpenseLineItem overridden = line("11800.00", "1", report);
        service.applySnapshot(overridden, null, new BigDecimal("1620.00"), "Composition dealer - lower rate on bill", null, 2);
        when(expenseLineItemRepository.findByReport_ReportId(report.getReportId())).thenReturn(List.of(calculated, overridden));

        service.freezeForSubmission(report);

        assertThat(calculated.getTaxSnapshotAt()).isNotNull();
        assertThat(overridden.getTaxSnapshotAt()).isNotNull();
        verify(taxAuditService).record(eq("ExpenseLineItem"), eq(overridden.getLineItemId()), eq("TAX_OVERRIDE"), eq(null), any(),
                eq(AuditSource.EMPLOYEE), eq("Composition dealer - lower rate on bill"));
        verify(taxAuditService).record(eq("ExpenseLineItem"), eq(calculated.getLineItemId()), eq("TAX_SNAPSHOT_FROZEN"), eq(null), any(),
                eq(AuditSource.SYSTEM), eq(null));
    }

    @Test
    void attachOcrEvidence_matchingReceipt_marksTheLineMatched_andAuditsIt() {
        ExpenseLineItem line = line("1180.00", "1", null);
        service.applySnapshot(line, null, null, null, null, 2);

        service.attachOcrEvidence(line, new BigDecimal("180.40"), new BigDecimal("0.95"));

        assertThat(line.getOcrTaxAmount()).isEqualByComparingTo("180.40");
        assertThat(line.getTaxValidationStatus()).isEqualTo(com.expense_management_service.enums.TaxValidationStatus.MATCHED);
        verify(taxAuditService).record(eq("ExpenseLineItem"), eq(line.getLineItemId()), eq("OCR_TAX_ATTACHED"), any(), any(),
                eq(AuditSource.OCR), eq(null));
    }

    @Test
    void attachOcrEvidence_disagreeingReceipt_isAMismatch_promotedToFinanceReviewAtSubmission() {
        ExpenseReport report = ExpenseReport.builder().reportId(UUID.randomUUID()).build();
        ExpenseLineItem line = line("1180.00", "1", report);
        service.applySnapshot(line, null, null, null, null, 2);
        service.attachOcrEvidence(line, new BigDecimal("18.00"), new BigDecimal("0.95"));
        assertThat(line.getTaxValidationStatus()).isEqualTo(com.expense_management_service.enums.TaxValidationStatus.MISMATCH);
        assertThat(line.getTaxValidationReasons()).contains("OCR_MISMATCH");

        when(expenseLineItemRepository.findByReport_ReportId(report.getReportId())).thenReturn(List.of(line));
        service.freezeForSubmission(report);

        assertThat(line.getTaxValidationStatus()).isEqualTo(com.expense_management_service.enums.TaxValidationStatus.REQUIRES_FINANCE_REVIEW);
    }

    @Test
    void ocrEvidence_survivesAResave() {
        ExpenseLineItem line = line("1180.00", "1", null);
        service.applySnapshot(line, null, null, null, null, 2);
        service.attachOcrEvidence(line, new BigDecimal("18.00"), null);

        service.applySnapshot(line, null, new BigDecimal("18.00"), "Receipt shows 18", service.describe(line), 2);

        // Entered now agrees with OCR but not with GST18: a configuration mismatch, not an OCR one.
        assertThat(line.getTaxValidationStatus()).isEqualTo(com.expense_management_service.enums.TaxValidationStatus.WARNING);
        assertThat(line.getTaxValidationReasons()).contains("OVERRIDE").contains("CONFIG_MISMATCH");
    }

    @Test
    void financeAdjust_changesTaxAndItc_marksAdjusted_andAuditsWithTheReason() {
        ExpenseLineItem line = line("11800.00", "1", ExpenseReport.builder().reportId(UUID.randomUUID()).build());
        service.applySnapshot(line, null, null, null, null, 2);
        BigDecimal gross = line.getAmount();
        // No code in the request keeps the line's own code, looked up by id.
        when(taxCodeRepository.findById(gst18.getTaxCodeId())).thenReturn(Optional.of(gst18));

        service.financeAdjust(line, null, new BigDecimal("1620.00"), new BigDecimal("100"), "Receipt shows 1,620 GST");

        assertThat(line.getAmount()).isEqualByComparingTo(gross);
        assertThat(line.getTaxAmount()).isEqualByComparingTo("1620.00");
        assertThat(line.getNetAmount()).isEqualByComparingTo("10180.00");
        assertThat(line.getTaxSource()).isEqualTo(TaxSource.FINANCE_ADJUSTED);
        assertThat(line.getTaxValidationStatus()).isEqualTo(com.expense_management_service.enums.TaxValidationStatus.FINANCE_ADJUSTED);
        assertThat(line.getRecoverableTaxAmount()).isEqualByComparingTo("1620.00");
        assertThat(line.getTaxSnapshotAt()).isNotNull();
        verify(taxAuditService).record(eq("ExpenseLineItem"), eq(line.getLineItemId()), eq("TAX_FINANCE_ADJUSTED"), any(), any(),
                eq(AuditSource.FINANCE), eq("Receipt shows 1,620 GST"));
    }

    @Test
    void financeAdjust_requiresAReason_andNeverTaxAboveTheGross() {
        ExpenseLineItem line = line("1180.00", "1", null);
        service.applySnapshot(line, null, null, null, null, 2);

        assertThatThrownBy(() -> service.financeAdjust(line, null, BigDecimal.TEN, null, " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> service.financeAdjust(line, null, new BigDecimal("2000"), null, "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gross");
    }

    @Test
    void correctionEdit_ofASubmittedReport_stampsTaxRevisedAt() {
        ExpenseReport corrected = ExpenseReport.builder().reportId(UUID.randomUUID()).submittedAt(LocalDateTime.now()).build();
        ExpenseLineItem line = line("1180.00", "1", corrected);
        service.applySnapshot(line, null, null, null, null, 2);
        assertThat(line.getTaxRevisedAt()).isNull();

        service.applySnapshot(line, null, new BigDecimal("50.00"), "Receipt shows 50", service.describe(line), 2);

        assertThat(line.getTaxRevisedAt()).isNotNull();
    }
}
