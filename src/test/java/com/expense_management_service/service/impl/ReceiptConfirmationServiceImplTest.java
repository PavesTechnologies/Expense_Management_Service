package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ExpenseLineItemRequest;
import com.expense_management_service.dto.request.ReceiptConfirmRequest;
import com.expense_management_service.dto.response.ExpenseLineItemResponse;
import com.expense_management_service.dto.response.ReceiptConfirmResponse;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.entity.ReceiptOcr;
import com.expense_management_service.enums.OcrStatus;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ReceiptOcrRepository;
import com.expense_management_service.repository.ReceiptRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.ExpenseLineItemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
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
class ReceiptConfirmationServiceImplTest {

    @Mock
    private ReceiptRepository receiptRepository;
    @Mock
    private ReceiptOcrRepository receiptOcrRepository;
    @Mock
    private ExpenseLineItemRepository expenseLineItemRepository;
    @Mock
    private ExpenseLineItemService expenseLineItemService;
    @Mock
    private CurrencyRepository currencyRepository;
    @Mock
    private CurrentUserService currentUserService;

    private ReceiptConfirmationServiceImpl confirmationService;

    private final String employeeId = "5100014";
    private UUID receiptId;
    private UUID reportId;
    private Receipt receipt;

    @BeforeEach
    void setUp() {
        confirmationService = new ReceiptConfirmationServiceImpl(receiptRepository, receiptOcrRepository,
                expenseLineItemRepository, expenseLineItemService, currencyRepository, currentUserService);

        reportId = UUID.randomUUID();
        ExpenseReport report = ExpenseReport.builder().reportId(reportId).employeeId(employeeId).build();
        receiptId = UUID.randomUUID();
        receipt = Receipt.builder().receiptId(receiptId).report(report).employeeId(employeeId).build();

        // lenient: the not-found test never reaches assertOwnerOrAdmin/save.
        lenient().when(currentUserService.getCurrentUser())
                .thenReturn(new CurrentUser(UUID.randomUUID(), employeeId, "jordan@example.com", "Jordan", List.of("GENERAL"), List.of()));
        when(receiptRepository.findById(receiptId)).thenReturn(Optional.of(receipt));
        lenient().when(receiptRepository.save(any(Receipt.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ReceiptOcr completedOcr() {
        return ReceiptOcr.builder().receipt(receipt).processingStatus(OcrStatus.OCR_COMPLETED)
                .merchantName("Acme Taxi").receiptDate(LocalDate.of(2026, 1, 15))
                .amount(new BigDecimal("123.45")).taxAmount(new BigDecimal("10.00")).currencyCode("USD").build();
    }

    private ReceiptConfirmRequest requestWithCategoryOnly(UUID categoryId) {
        return new ReceiptConfirmRequest(null, categoryId, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void confirm_createsNewLineItem_usingOcrDefaults_whenRequestOmitsFields() {
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId))
                .thenReturn(Optional.of(completedOcr()));
        when(currencyRepository.findByCurrencyCodeIgnoreCase("USD"))
                .thenReturn(Optional.of(Currency.builder().currencyId(UUID.randomUUID()).currencyCode("USD").build()));
        UUID categoryId = UUID.randomUUID();
        UUID lineItemId = UUID.randomUUID();
        when(expenseLineItemService.create(eq(reportId), any(ExpenseLineItemRequest.class)))
                .thenReturn(sampleLineItemResponse(lineItemId));
        ExpenseLineItem createdEntity = ExpenseLineItem.builder().lineItemId(lineItemId).build();
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(createdEntity));

        ReceiptConfirmResponse response = confirmationService.confirm(receiptId, requestWithCategoryOnly(categoryId));

        assertThat(response.lineItemId()).isEqualTo(lineItemId);
        assertThat(response.receiptStatus()).isEqualTo(OcrStatus.VERIFIED);
        assertThat(response.amountMismatch()).isFalse();
        assertThat(receipt.getOcrStatus()).isEqualTo("VERIFIED");
        assertThat(receipt.getLineItem()).isSameAs(createdEntity);

        ArgumentCaptor<ExpenseLineItemRequest> captor = ArgumentCaptor.forClass(ExpenseLineItemRequest.class);
        verify(expenseLineItemService).create(eq(reportId), captor.capture());
        assertThat(captor.getValue().merchantName()).isEqualTo("Acme Taxi");
        assertThat(captor.getValue().amount()).isEqualByComparingTo("123.45");
        assertThat(captor.getValue().taxAmount()).isEqualByComparingTo("10.00");
        assertThat(captor.getValue().expenseDate()).isEqualTo(LocalDate.of(2026, 1, 15));

        assertThat(createdEntity.getCreatedBy()).isEqualTo("OCR");
    }

    @Test
    void confirm_employeeSuppliedValues_takePrecedenceOverOcr() {
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId))
                .thenReturn(Optional.of(completedOcr()));
        UUID categoryId = UUID.randomUUID();
        UUID currencyId = UUID.randomUUID();
        UUID lineItemId = UUID.randomUUID();
        ReceiptConfirmRequest request = new ReceiptConfirmRequest(null, categoryId, LocalDate.of(2026, 2, 1),
                "Corrected Merchant", "biz trip", new BigDecimal("999.99"), currencyId, new BigDecimal("50.00"), null, null, true);
        when(expenseLineItemService.create(eq(reportId), any(ExpenseLineItemRequest.class)))
                .thenReturn(sampleLineItemResponse(lineItemId));
        when(expenseLineItemRepository.findById(lineItemId))
                .thenReturn(Optional.of(ExpenseLineItem.builder().lineItemId(lineItemId).build()));

        confirmationService.confirm(receiptId, request);

        ArgumentCaptor<ExpenseLineItemRequest> captor = ArgumentCaptor.forClass(ExpenseLineItemRequest.class);
        verify(expenseLineItemService).create(eq(reportId), captor.capture());
        assertThat(captor.getValue().merchantName()).isEqualTo("Corrected Merchant");
        assertThat(captor.getValue().amount()).isEqualByComparingTo("999.99");
        assertThat(captor.getValue().currencyId()).isEqualTo(currencyId);
        verify(currencyRepository, never()).findByCurrencyCodeIgnoreCase(any());
    }

    @Test
    void confirm_resolvesCurrencyFromOcrCode_whenRequestOmitsCurrencyId() {
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId))
                .thenReturn(Optional.of(completedOcr()));
        UUID categoryId = UUID.randomUUID();
        UUID resolvedCurrencyId = UUID.randomUUID();
        Currency usd = Currency.builder().currencyId(resolvedCurrencyId).currencyCode("USD").build();
        when(currencyRepository.findByCurrencyCodeIgnoreCase("USD")).thenReturn(Optional.of(usd));
        UUID lineItemId = UUID.randomUUID();
        when(expenseLineItemService.create(eq(reportId), any(ExpenseLineItemRequest.class)))
                .thenReturn(sampleLineItemResponse(lineItemId));
        when(expenseLineItemRepository.findById(lineItemId))
                .thenReturn(Optional.of(ExpenseLineItem.builder().lineItemId(lineItemId).build()));

        confirmationService.confirm(receiptId, requestWithCategoryOnly(categoryId));

        ArgumentCaptor<ExpenseLineItemRequest> captor = ArgumentCaptor.forClass(ExpenseLineItemRequest.class);
        verify(expenseLineItemService).create(eq(reportId), captor.capture());
        assertThat(captor.getValue().currencyId()).isEqualTo(resolvedCurrencyId);
    }

    @Test
    void confirm_throwsIllegalArgument_whenCurrencyCannotBeResolved() {
        ReceiptOcr ocrWithUnknownCurrency = ReceiptOcr.builder().receipt(receipt).processingStatus(OcrStatus.OCR_COMPLETED)
                .amount(new BigDecimal("50.00")).receiptDate(LocalDate.of(2026, 1, 1)).currencyCode("XYZ").build();
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId))
                .thenReturn(Optional.of(ocrWithUnknownCurrency));
        when(currencyRepository.findByCurrencyCodeIgnoreCase("XYZ")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> confirmationService.confirm(receiptId, requestWithCategoryOnly(UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currencyId");
    }

    @Test
    void confirm_throwsIllegalArgument_whenCategoryIdMissingForNewLineItem() {
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId))
                .thenReturn(Optional.of(completedOcr()));

        assertThatThrownBy(() -> confirmationService.confirm(receiptId, requestWithCategoryOnly(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("categoryId");

        verify(expenseLineItemService, never()).create(any(), any());
    }

    @Test
    void confirm_throwsIllegalArgument_whenAmountMissing_andNoOcrData() {
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> confirmationService.confirm(receiptId, requestWithCategoryOnly(UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void confirm_attachesToExistingLineItem_andFlagsAmountMismatch() {
        UUID lineItemId = UUID.randomUUID();
        ExpenseReport report = receipt.getReport();
        ExpenseLineItem existing = ExpenseLineItem.builder().lineItemId(lineItemId).report(report).amount(new BigDecimal("50.00")).build();
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(existing));
        ReceiptOcr ocr = completedOcr(); // amount 123.45
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId)).thenReturn(Optional.of(ocr));

        ReceiptConfirmRequest request = new ReceiptConfirmRequest(lineItemId, null, null, null, null, null, null, null, null, null, null);
        ReceiptConfirmResponse response = confirmationService.confirm(receiptId, request);

        assertThat(response.amountMismatch()).isTrue();
        assertThat(response.enteredAmount()).isEqualByComparingTo("50.00");
        assertThat(response.extractedAmount()).isEqualByComparingTo("123.45");
        assertThat(receipt.getLineItem()).isSameAs(existing);
        verify(expenseLineItemService, never()).create(any(), any());
    }

    @Test
    void confirm_attachesToExistingLineItem_noMismatch_whenAmountsMatch() {
        UUID lineItemId = UUID.randomUUID();
        ExpenseReport report = receipt.getReport();
        ExpenseLineItem existing = ExpenseLineItem.builder().lineItemId(lineItemId).report(report).amount(new BigDecimal("123.45")).build();
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(existing));
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId)).thenReturn(Optional.of(completedOcr()));

        ReceiptConfirmRequest request = new ReceiptConfirmRequest(lineItemId, null, null, null, null, null, null, null, null, null, null);
        ReceiptConfirmResponse response = confirmationService.confirm(receiptId, request);

        assertThat(response.amountMismatch()).isFalse();
    }

    @Test
    void confirm_throwsIllegalArgument_whenExistingLineItemBelongsToDifferentReport() {
        UUID lineItemId = UUID.randomUUID();
        ExpenseReport otherReport = ExpenseReport.builder().reportId(UUID.randomUUID()).employeeId(employeeId).build();
        ExpenseLineItem existing = ExpenseLineItem.builder().lineItemId(lineItemId).report(otherReport).amount(new BigDecimal("50.00")).build();
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(existing));
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId)).thenReturn(Optional.empty());

        ReceiptConfirmRequest request = new ReceiptConfirmRequest(lineItemId, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> confirmationService.confirm(receiptId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void confirm_throwsAccessDenied_whenCallerDoesNotOwnReceipt() {
        receipt.setEmployeeId("someone-else");

        assertThatThrownBy(() -> confirmationService.confirm(receiptId, requestWithCategoryOnly(UUID.randomUUID())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void confirm_throwsResourceNotFound_whenReceiptMissing() {
        when(receiptRepository.findById(receiptId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> confirmationService.confirm(receiptId, requestWithCategoryOnly(UUID.randomUUID())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private ExpenseLineItemResponse sampleLineItemResponse(UUID lineItemId) {
        return new ExpenseLineItemResponse(lineItemId, reportId, "EXP-001", "DRAFT", UUID.randomUUID(), "Travel",
                true, false, null, LocalDate.of(2026, 1, 15), "Acme Taxi", null, new BigDecimal("123.45"),
                UUID.randomUUID(), "USD", BigDecimal.ONE, new BigDecimal("123.45"), "USD",
                new BigDecimal("10.00"), new BigDecimal("113.45"), null, null, null, null, true, "ACTIVE", null, null, List.of());
    }
}
