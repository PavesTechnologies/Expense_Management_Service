package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.dto.request.InvoiceHandoffRequest;
import com.expense_management_service.dto.response.InvoiceHandoffEligibleExpenseResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.InvoiceSync;
import com.expense_management_service.enums.InvoiceHandoffStatus;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.mapper.InvoiceSyncMapper;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.InvoiceSyncRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the invoice-team handoff queue (Epic 8). Mirrors {@code
 * ExpenseLineItemServiceImplTest}'s hand-constructed-service convention. The eligibility
 * rule itself lives in the repository's hand-written JPQL ({@code
 * ExpenseLineItemRepository.findEligibleForInvoiceHandoff}), which a pure Mockito test can't
 * exercise — these tests cover the service logic around it (mapping, duplicate-handoff
 * guarding, independence from AP reimbursement).
 */
@ExtendWith(MockitoExtension.class)
class InvoiceHandoffServiceImplTest {

    @Mock
    private ExpenseLineItemRepository expenseLineItemRepository;
    @Mock
    private InvoiceSyncRepository invoiceSyncRepository;
    @Mock
    private ExpenseReportRepository expenseReportRepository;

    private InvoiceHandoffServiceImpl service;

    private UUID lineItemId;
    private ExpenseLineItem eligibleLineItem;

    @BeforeEach
    void setUp() {
        service = new InvoiceHandoffServiceImpl(expenseLineItemRepository, expenseReportRepository, invoiceSyncRepository, new InvoiceSyncMapper());
        ReflectionTestUtils.setField(service, "baseCurrencyCode", "INR");

        lineItemId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        Currency currency = Currency.builder().currencyId(UUID.randomUUID()).currencyCode("USD").status("ACTIVE").build();
        ExpenseCategory category = ExpenseCategory.builder().categoryId(UUID.randomUUID()).categoryName("Travel").status("ACTIVE").build();
        ExpenseReport report = ExpenseReport.builder().reportId(UUID.randomUUID()).reportNumber("ER-001")
                .employeeId("emp-1").reportStatus(ReportStatus.APPROVED).build();
        eligibleLineItem = ExpenseLineItem.builder()
                .lineItemId(lineItemId).report(report).category(category).currency(currency)
                .expenseDate(LocalDate.now().minusDays(2)).amount(new BigDecimal("100.00"))
                .baseAmount(new BigDecimal("100.00")).taxAmount(BigDecimal.ZERO).netAmount(new BigDecimal("100.00"))
                .clientBillable(true).resolvedClientId(clientId).resolvedClientName("Aurora Health").build();
    }

    @Test
    void getEligibleExpenses_mapsFinanceVerifiedBillableExpense() {
        Page<ExpenseLineItem> page = new PageImpl<>(List.of(eligibleLineItem), PageRequest.of(0, 20), 1);
        when(expenseLineItemRepository.findEligibleForInvoiceHandoff(null, null, null, null, PageRequest.of(0, 20)))
                .thenReturn(page);

        PageResponse<InvoiceHandoffEligibleExpenseResponse> result =
                service.getEligibleExpenses(null, null, null, null, 0, 20);

        assertThat(result.content()).hasSize(1);
        InvoiceHandoffEligibleExpenseResponse response = result.content().get(0);
        assertThat(response.lineItemId()).isEqualTo(lineItemId);
        assertThat(response.resolvedClientName()).isEqualTo("Aurora Health");
        assertThat(response.baseCurrencyCode()).isEqualTo("INR");
    }

    @Test
    void markHandedOff_createsInvoiceSyncRecord_whenNotAlreadyHandedOff() {
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(eligibleLineItem));
        when(invoiceSyncRepository.existsByLineItem_LineItemIdAndSyncStatus(lineItemId, "HANDED_OFF")).thenReturn(false);
        when(invoiceSyncRepository.save(any(InvoiceSync.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.markHandedOff(lineItemId, new InvoiceHandoffRequest("INV-100", null, null));

        assertThat(response.invoiceReference()).isEqualTo("INV-100");
        assertThat(response.syncStatus()).isEqualTo("HANDED_OFF");
    }

    @Test
    void markHandedOff_throwsDuplicateResourceException_whenAlreadyHandedOffAndNotReprocessing() {
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(eligibleLineItem));
        when(invoiceSyncRepository.existsByLineItem_LineItemIdAndSyncStatus(lineItemId, "HANDED_OFF")).thenReturn(true);

        assertThatThrownBy(() -> service.markHandedOff(lineItemId, new InvoiceHandoffRequest(null, null, null)))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("already been handed off");

        verify(invoiceSyncRepository, never()).save(any());
    }

    @Test
    void markHandedOff_throwsBusinessRuleViolation_whenReprocessingWithoutReason() {
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(eligibleLineItem));
        when(invoiceSyncRepository.existsByLineItem_LineItemIdAndSyncStatus(lineItemId, "HANDED_OFF")).thenReturn(true);

        assertThatThrownBy(() -> service.markHandedOff(lineItemId, new InvoiceHandoffRequest(null, true, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("reason is required");
    }

    @Test
    void markHandedOff_allowsReprocess_whenReasonProvided() {
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(eligibleLineItem));
        when(invoiceSyncRepository.existsByLineItem_LineItemIdAndSyncStatus(lineItemId, "HANDED_OFF")).thenReturn(true);
        when(invoiceSyncRepository.save(any(InvoiceSync.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = service.markHandedOff(lineItemId, new InvoiceHandoffRequest(null, true, "Invoice was voided, reissuing"));

        assertThat(response.remarks()).contains("Invoice was voided, reissuing");
    }

    @Test
    void markHandedOff_completesReportInvoiceTrack_onlyOnceEveryBillableLineIsHandedOff() {
        ExpenseReport report = eligibleLineItem.getReport();
        report.setInvoiceHandoffStatus(InvoiceHandoffStatus.PENDING);
        report.setPaymentRoutingStatus(PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
        UUID otherLineItemId = UUID.randomUUID();
        ExpenseLineItem otherBillable = ExpenseLineItem.builder()
                .lineItemId(otherLineItemId).report(report).clientBillable(true).build();
        ExpenseLineItem internal = ExpenseLineItem.builder()
                .lineItemId(UUID.randomUUID()).report(report).clientBillable(false).build();
        report.getExpenseLineItems().addAll(List.of(eligibleLineItem, otherBillable, internal));
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(eligibleLineItem));
        when(invoiceSyncRepository.save(any(InvoiceSync.class))).thenAnswer(inv -> inv.getArgument(0));

        // First billable line handed off, second still outstanding -> stays PENDING.
        when(invoiceSyncRepository.existsByLineItem_LineItemIdAndSyncStatus(lineItemId, "HANDED_OFF")).thenReturn(false, true);
        when(invoiceSyncRepository.existsByLineItem_LineItemIdAndSyncStatus(otherLineItemId, "HANDED_OFF")).thenReturn(false);
        service.markHandedOff(lineItemId, new InvoiceHandoffRequest("INV-1", false, null));
        assertThat(report.getInvoiceHandoffStatus()).isEqualTo(InvoiceHandoffStatus.PENDING);

        // Second billable line handed off -> COMPLETED; the AP track is never touched.
        when(expenseLineItemRepository.findById(otherLineItemId)).thenReturn(Optional.of(otherBillable));
        when(invoiceSyncRepository.existsByLineItem_LineItemIdAndSyncStatus(otherLineItemId, "HANDED_OFF")).thenReturn(false, true);
        service.markHandedOff(otherLineItemId, new InvoiceHandoffRequest("INV-2", false, null));
        assertThat(report.getInvoiceHandoffStatus()).isEqualTo(InvoiceHandoffStatus.COMPLETED);
        assertThat(report.getPaymentRoutingStatus()).isEqualTo(PaymentRoutingStatus.APPROVED_FOR_PAYMENT);
    }

    @Test
    void getSummary_countsAndTotalsReadyExpenses_andHandedOffRecords() {
        ExpenseLineItem second = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).report(eligibleLineItem.getReport())
                .baseAmount(new BigDecimal("50.00")).clientBillable(true).build();
        when(expenseLineItemRepository.findEligibleForInvoiceHandoff(isNull(), isNull(), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(eligibleLineItem, second)));
        when(invoiceSyncRepository.countBySyncStatus("HANDED_OFF")).thenReturn(7L);

        var summary = service.getSummary();

        assertThat(summary.readyCount()).isEqualTo(2);
        assertThat(summary.readyBaseAmount()).isEqualByComparingTo("150.00");
        assertThat(summary.handedOffCount()).isEqualTo(7);
        assertThat(summary.baseCurrencyCode()).isEqualTo("INR");
    }

    @Test
    void getHandedOff_mapsHandoffWithItsExpense() {
        InvoiceSync sync = InvoiceSync.builder().syncId(UUID.randomUUID()).lineItem(eligibleLineItem)
                .invoiceReference("INV-9").syncStatus("HANDED_OFF").syncDate(java.time.LocalDateTime.now()).build();
        when(invoiceSyncRepository.findBySyncStatusOrderBySyncDateDesc(eq("HANDED_OFF"), any()))
                .thenReturn(new PageImpl<>(List.of(sync)));

        var page = service.getHandedOff(0, 20);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).invoiceReference()).isEqualTo("INV-9");
        assertThat(page.content().get(0).reportNumber()).isEqualTo("ER-001");
    }

    @Test
    void getHandoffHistory_returnsMappedRecords() {
        InvoiceSync sync = InvoiceSync.builder().syncId(UUID.randomUUID()).lineItem(eligibleLineItem)
                .invoiceReference("INV-1").syncStatus("HANDED_OFF").build();
        when(invoiceSyncRepository.findByLineItem_LineItemIdOrderBySyncDateDesc(lineItemId)).thenReturn(List.of(sync));

        assertThat(service.getHandoffHistory(lineItemId)).hasSize(1);
    }
}
