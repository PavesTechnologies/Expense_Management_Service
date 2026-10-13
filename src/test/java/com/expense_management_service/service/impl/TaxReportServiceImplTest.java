package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.TaxReportResponse;
import com.expense_management_service.enums.TaxComponentCode;
import com.expense_management_service.enums.TaxSource;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaxReportServiceImplTest {

    @Mock
    private EntityManager em;
    @Mock
    private TypedQuery<Object[]> query;

    private TaxReportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TaxReportServiceImpl();
        ReflectionTestUtils.setField(service, "em", em);
        ReflectionTestUtils.setField(service, "baseCurrencyCode", "INR");
        lenient().when(query.setParameter(anyString(), any())).thenReturn(query);
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private void rows(String jpqlFragment, List<Object[]> result) {
        when(em.createQuery(contains(jpqlFragment), eq(Object[].class))).thenReturn(query);
        when(query.getResultList()).thenReturn(result);
    }

    @Test
    void byTaxCode_mergesSourcesPerCode_andGroupsLegacyLinesSeparately() {
        rows("group by l.taxCode, l.taxSource", List.of(
                new Object[]{"GST18", TaxSource.CALCULATED, 2L, d("23600"), d("3600"), d("1800"), d("20000")},
                new Object[]{"GST18", TaxSource.EMPLOYEE_OVERRIDE, 1L, d("11800"), d("1620"), d("0"), d("10180")},
                new Object[]{null, TaxSource.LEGACY, 3L, d("5000"), d("300"), d("0"), d("4700")},
                new Object[]{null, TaxSource.NONE, 1L, d("400"), d("0"), d("0"), d("400")}));

        TaxReportResponse r = service.report(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 30), "taxCode", null, null);

        assertThat(r.rows()).extracting(TaxReportResponse.Row::key).containsExactly("GST18", "LEGACY", "NONE");
        TaxReportResponse.Row gst = r.rows().get(0);
        assertThat(gst.lineCount()).isEqualTo(3);
        assertThat(gst.taxAmount()).isEqualByComparingTo("5220");
        assertThat(gst.recoverableTaxAmount()).isEqualByComparingTo("1800");
        assertThat(gst.nonRecoverableTaxAmount()).isEqualByComparingTo("3420");
        assertThat(r.rows().get(1).label()).isEqualTo("Entered before tax codes");
        assertThat(r.totals().taxAmount()).isEqualByComparingTo("5520");
        assertThat(r.totals().grossAmount()).isEqualByComparingTo("40800");
        assertThat(r.currency()).isEqualTo("INR");
    }

    @Test
    void byMonth_fillsEveryMonthOfTheWindow() {
        rows("group by year(l.expenseDate)", List.<Object[]>of(
                new Object[]{2026, 8, 4L, d("11800"), d("1800"), d("900"), d("10000")}));

        TaxReportResponse r = service.report(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), "month", null, null);

        assertThat(r.rows()).extracting(TaxReportResponse.Row::key).containsExactly("2026-07", "2026-08", "2026-09");
        assertThat(r.rows().get(0).taxAmount()).isEqualByComparingTo("0");
        assertThat(r.rows().get(1).taxAmount()).isEqualByComparingTo("1800");
        assertThat(r.rows().get(1).label()).isEqualTo("Aug");
    }

    @Test
    void byComponent_hasNoGrossOrNet() {
        rows("from ExpenseLineTaxComponent", List.of(
                new Object[]{TaxComponentCode.CGST, 2L, d("900"), d("450")},
                new Object[]{TaxComponentCode.IGST, 1L, d("1800"), d("0")}));

        TaxReportResponse r = service.report(null, null, "component", null, null);

        assertThat(r.rows()).extracting(TaxReportResponse.Row::key).containsExactly("IGST", "CGST");
        assertThat(r.rows().get(0).grossAmount()).isNull();
        assertThat(r.totals().grossAmount()).isNull();
        assertThat(r.totals().taxAmount()).isEqualByComparingTo("2700");
    }

    @SuppressWarnings("unchecked")
    private void lines(List<com.expense_management_service.entity.ExpenseLineItem> result) {
        TypedQuery<com.expense_management_service.entity.ExpenseLineItem> lineQuery = org.mockito.Mockito.mock(TypedQuery.class);
        when(lineQuery.setParameter(anyString(), any())).thenReturn(lineQuery);
        when(em.createQuery(contains("join fetch l.report"), eq(com.expense_management_service.entity.ExpenseLineItem.class))).thenReturn(lineQuery);
        when(lineQuery.getResultList()).thenReturn(result);
    }

    private static com.expense_management_service.entity.ExpenseLineItem taxedLine(String tax, String recoverable, String itcPercent,
            com.expense_management_service.enums.TaxValidationStatus status, String gstin) {
        var report = com.expense_management_service.entity.ExpenseReport.builder().reportId(java.util.UUID.randomUUID())
                .reportNumber("ER-1").employeeId("emp-1").reportStatus(com.expense_management_service.enums.ReportStatus.APPROVED).build();
        return com.expense_management_service.entity.ExpenseLineItem.builder().lineItemId(java.util.UUID.randomUUID()).report(report)
                .expenseDate(LocalDate.of(2026, 9, 5)).merchantName("Hotel").taxCode("GST18")
                .baseTaxAmount(d(tax)).baseRecoverableTaxAmount(d(recoverable)).itcRecoverablePercent(d(itcPercent))
                .taxValidationStatus(status).supplierGstin(gstin).build();
    }

    @Test
    void itcRegister_splitsRecoverableIntoVerifiedAndUnverified_andCountsCreditWithoutGstin() {
        lines(List.of(
                taxedLine("1800", "1800", "100", com.expense_management_service.enums.TaxValidationStatus.FINANCE_VERIFIED, "29ABCDE1234F1Z5"),
                taxedLine("900", "450", "50", com.expense_management_service.enums.TaxValidationStatus.REQUIRES_FINANCE_REVIEW, null),
                taxedLine("120", "0", "0", com.expense_management_service.enums.TaxValidationStatus.MATCHED, null)));

        var r = service.itcRegister(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null, null, null);

        assertThat(r.lines()).extracting(com.expense_management_service.dto.response.ItcRegisterResponse.Line::itcStatus)
                .containsExactly("VERIFIED", "UNVERIFIED", "NOT_RECOVERABLE");
        assertThat(r.lines().get(1).nonRecoverableTaxAmount()).isEqualByComparingTo("450");
        assertThat(r.totals().lineCount()).isEqualTo(3);
        assertThat(r.totals().taxAmount()).isEqualByComparingTo("2820");
        assertThat(r.totals().verifiedRecoverableTaxAmount()).isEqualByComparingTo("1800");
        assertThat(r.totals().unverifiedRecoverableTaxAmount()).isEqualByComparingTo("450");
        assertThat(r.totals().recoverableTaxAmount()).isEqualByComparingTo("2250");
        assertThat(r.totals().nonRecoverableTaxAmount()).isEqualByComparingTo("570");
        assertThat(r.totals().recoverableWithoutGstinCount()).isEqualTo(1);
        assertThat(r.currency()).isEqualTo("INR");
    }

    @Test
    void itcRegister_filtersByItcStatus_andTotalsOnlyTheShownLines() {
        lines(List.of(
                taxedLine("1800", "1800", "100", com.expense_management_service.enums.TaxValidationStatus.FINANCE_ADJUSTED, null),
                taxedLine("900", "450", "50", com.expense_management_service.enums.TaxValidationStatus.MATCHED, null)));

        var r = service.itcRegister(null, null, com.expense_management_service.enums.ItcStatus.UNVERIFIED, null, null);

        assertThat(r.lines()).hasSize(1);
        assertThat(r.totals().taxAmount()).isEqualByComparingTo("900");
        assertThat(r.totals().verifiedRecoverableTaxAmount()).isEqualByComparingTo("0");
        assertThat(r.to()).isEqualTo(LocalDate.now());
        assertThat(r.from()).isEqualTo(LocalDate.now().withDayOfMonth(1));
    }

    @Test
    void reports_leaveOutRejectedReports() {
        rows("group by year(l.expenseDate)", List.of());

        service.report(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "month", null, null);

        org.mockito.Mockito.verify(query).setParameter(eq("notSubmitted"),
                org.mockito.ArgumentMatchers.<java.util.Set<?>>argThat(s -> s.contains(com.expense_management_service.enums.ReportStatus.REJECTED)));
    }

    @Test
    void rejectsAnUnknownGrouping_andAnInvertedWindow() {
        assertThatThrownBy(() -> service.report(null, null, "employee", null, null)).hasMessageContaining("groupBy");
        assertThatThrownBy(() -> service.report(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 1, 1), "month", null, null))
                .hasMessageContaining("from cannot be after to");
    }
}
