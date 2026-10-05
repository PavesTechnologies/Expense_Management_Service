package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.TaxJournalResponse;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.GlAccount;
import com.expense_management_service.entity.TaxCode;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.repository.TaxCodeRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaxJournalServiceImplTest {

    @Mock
    private EntityManager em;
    @Mock
    private TypedQuery<ExpenseReport> query;
    @Mock
    private TaxCodeRepository taxCodeRepository;
    @Mock
    private SystemConfigurationRepository systemConfigurationRepository;

    private TaxJournalServiceImpl service;
    private final GlAccount travelGl = GlAccount.builder().glAccountCode("6100").glAccountName("Travel").build();
    private final GlAccount inputGstGl = GlAccount.builder().glAccountCode("1500").glAccountName("Input GST").build();

    @BeforeEach
    void setUp() {
        service = new TaxJournalServiceImpl(taxCodeRepository, systemConfigurationRepository);
        ReflectionTestUtils.setField(service, "em", em);
        ReflectionTestUtils.setField(service, "baseCurrencyCode", "INR");
        lenient().when(em.createQuery(anyString(), eq(ExpenseReport.class))).thenReturn(query);
        lenient().when(query.setParameter(anyString(), any())).thenReturn(query);
        lenient().when(systemConfigurationRepository.findByConfigKey(any())).thenReturn(Optional.empty());
    }

    private ExpenseLineItem line(ExpenseReport report, GlAccount gl, String gross, String tax, String recoverable, UUID taxCodeId) {
        ExpenseLineItem line = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).report(report)
                .category(ExpenseCategory.builder().categoryName("Travel").glAccount(gl).build())
                .baseAmount(new BigDecimal(gross)).baseTaxAmount(new BigDecimal(tax))
                .baseRecoverableTaxAmount(new BigDecimal(recoverable)).taxCodeId(taxCodeId).taxCode(taxCodeId != null ? "GST18" : null)
                .build();
        report.getExpenseLineItems().add(line);
        return line;
    }

    private ExpenseReport report() {
        return ExpenseReport.builder().reportId(UUID.randomUUID()).reportNumber("ER-100").employeeId("5100014")
                .approvedAt(LocalDateTime.of(2026, 9, 10, 12, 0)).expenseLineItems(new ArrayList<>()).build();
    }

    @Test
    void journal_debitsExpenseAndRecoverableTax_creditsEmployeePayable_andBalances() {
        UUID gst18 = UUID.randomUUID();
        when(taxCodeRepository.findById(gst18)).thenReturn(Optional.of(TaxCode.builder().inputTaxGlAccount(inputGstGl).build()));
        ExpenseReport report = report();
        line(report, travelGl, "11800.00", "1800.00", "900.00", gst18);   // 50% ITC
        line(report, travelGl, "500.00", "0.00", "0.00", null);
        when(query.getResultList()).thenReturn(List.of(report));

        TaxJournalResponse r = service.export(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        TaxJournalResponse.Journal j = r.journals().get(0);
        assertThat(j.entries()).extracting(TaxJournalResponse.Entry::accountCode).containsExactly("6100", "1500", "EMPLOYEE_PAYABLE");
        assertThat(j.entries().get(0).debit()).isEqualByComparingTo("11400.00"); // (11,800 - 900 recoverable) + 500
        assertThat(j.entries().get(1).debit()).isEqualByComparingTo("900.00");
        assertThat(j.entries().get(2).credit()).isEqualByComparingTo("12300.00");
        assertThat(j.balanced()).isTrue();
        assertThat(r.totalDebit()).isEqualByComparingTo(r.totalCredit());
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void unmappedAccounts_stillBalance_butAreWarned() {
        UUID gst18 = UUID.randomUUID();
        when(taxCodeRepository.findById(gst18)).thenReturn(Optional.of(TaxCode.builder().build()));
        ExpenseReport report = report();
        line(report, null, "1180.00", "180.00", "180.00", gst18);
        when(query.getResultList()).thenReturn(List.of(report));

        TaxJournalResponse r = service.export(null, null);

        TaxJournalResponse.Journal j = r.journals().get(0);
        assertThat(j.entries()).extracting(TaxJournalResponse.Entry::accountCode).contains("UNMAPPED");
        assertThat(j.balanced()).isTrue();
        assertThat(r.warnings()).hasSize(2).anyMatch(w -> w.contains("no GL account")).anyMatch(w -> w.contains("no input tax GL"));
    }
}
