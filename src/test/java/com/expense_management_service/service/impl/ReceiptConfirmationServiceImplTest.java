package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ExpenseLineItemRequest;
import com.expense_management_service.dto.request.ReceiptConfirmRequest;
import com.expense_management_service.dto.response.ExpenseLineItemResponse;
import com.expense_management_service.dto.response.ReceiptConfirmResponse;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.entity.ReceiptOcr;
import com.expense_management_service.enums.OcrStatus;
import com.expense_management_service.repository.AuditLogRepository;
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

    @org.mockito.Mock
    private com.expense_management_service.service.TaxSnapshotService taxSnapshotService;

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
    @Mock
    private AuditLogRepository auditLogRepository;

    private ReceiptConfirmationServiceImpl confirmationService;

    private final String employeeId = "5100014";
    private UUID receiptId;
    private UUID reportId;
    private Receipt receipt;

    @BeforeEach
    void setUp() {
        confirmationService = new ReceiptConfirmationServiceImpl(receiptRepository, receiptOcrRepository,
                expenseLineItemRepository, expenseLineItemService, currencyRepository, currentUserService, taxSnapshotService,
                auditLogRepository);

        reportId = UUID.randomUUID();
        ExpenseReport report = ExpenseReport.builder().reportId(reportId).employeeId(employeeId).build();
        receiptId = UUID.randomUUID();
        receipt = Receipt.builder().receiptId(receiptId).report(report).employeeId(employeeId).build();

        // lenient: the not-found test never reaches assertOwnerOrAdmin/save.
        lenient().when(currentUserService.getCurrentUser())
                .thenReturn(new CurrentUser(UUID.randomUUID(), null, employeeId, "jordan@example.com", "Jordan", List.of("GENERAL"), List.of()));
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

    // ---------------------------------------------------------------------
    // Receipt total (OCR) vs the amount entered for a new line item
    // ---------------------------------------------------------------------

    /** A new-line request in the receipt's own currency (USD), entering {@code amount} against OCR's 123.45. */
    private ReceiptConfirmRequest newLineRequest(String amount, String description, UUID currencyId) {
        return new ReceiptConfirmRequest(null, UUID.randomUUID(), LocalDate.of(2026, 1, 15), "Acme Taxi", description,
                new BigDecimal(amount), currencyId, null, null, null, false);
    }

    private UUID stubUsdLineCreation(UUID lineItemId) {
        UUID usdId = UUID.randomUUID();
        lenient().when(currencyRepository.findById(usdId))
                .thenReturn(Optional.of(Currency.builder().currencyId(usdId).currencyCode("USD").build()));
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId))
                .thenReturn(Optional.of(completedOcr()));
        lenient().when(expenseLineItemService.create(eq(reportId), any(ExpenseLineItemRequest.class)))
                .thenReturn(sampleLineItemResponse(lineItemId));
        lenient().when(expenseLineItemRepository.findById(lineItemId))
                .thenReturn(Optional.of(ExpenseLineItem.builder().lineItemId(lineItemId).build()));
        return usdId;
    }

    @Test
    void confirm_newLineItem_claimingLessThanTheReceipt_isAllowed_butFlaggedAndAudited() {
        UUID lineItemId = UUID.randomUUID();
        UUID usdId = stubUsdLineCreation(lineItemId);

        ReceiptConfirmResponse response = confirmationService.confirm(receiptId, newLineRequest("100.00", null, usdId));

        assertThat(response.amountMismatch()).isTrue();
        assertThat(response.enteredAmount()).isEqualByComparingTo("100.00");
        assertThat(response.extractedAmount()).isEqualByComparingTo("123.45");
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("RECEIPT_AMOUNT_MISMATCH");
        assertThat(audit.getValue().getEntityId()).isEqualTo(lineItemId);
        assertThat(audit.getValue().getOldValue()).contains("123.45");
        assertThat(audit.getValue().getNewValue()).contains("100.00");
        assertThat(audit.getValue().getPerformedBy()).isEqualTo(employeeId);
    }

    @Test
    void confirm_newLineItem_claimingMoreThanTheReceipt_withoutAnExplanation_isRejected() {
        UUID usdId = stubUsdLineCreation(UUID.randomUUID());

        assertThatThrownBy(() -> confirmationService.confirm(receiptId, newLineRequest("150.00", "  ", usdId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("123.45")
                .hasMessageContaining("150.00");

        verify(expenseLineItemService, never()).create(any(), any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void confirm_newLineItem_claimingMoreThanTheReceipt_withAnExplanation_isAllowed_andTheReasonIsAudited() {
        UUID lineItemId = UUID.randomUUID();
        UUID usdId = stubUsdLineCreation(lineItemId);

        ReceiptConfirmResponse response = confirmationService.confirm(receiptId,
                newLineRequest("150.00", "Tip paid in cash, not on the receipt", usdId));

        assertThat(response.amountMismatch()).isTrue();
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertThat(audit.getValue().getReason()).isEqualTo("Tip paid in cash, not on the receipt");
    }

    @Test
    void confirm_newLineItem_withinACentOfTheReceipt_isNotAMismatch() {
        UUID usdId = stubUsdLineCreation(UUID.randomUUID());

        ReceiptConfirmResponse response = confirmationService.confirm(receiptId, newLineRequest("123.46", null, usdId));

        assertThat(response.amountMismatch()).isFalse();
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void confirm_newLineItem_inAnotherCurrencyThanTheReceipt_isNotCompared() {
        stubUsdLineCreation(UUID.randomUUID());
        UUID inrId = UUID.randomUUID();
        when(currencyRepository.findById(inrId))
                .thenReturn(Optional.of(Currency.builder().currencyId(inrId).currencyCode("INR").build()));

        ReceiptConfirmResponse response = confirmationService.confirm(receiptId, newLineRequest("10250.00", null, inrId));

        assertThat(response.amountMismatch()).isFalse();
        verify(auditLogRepository, never()).save(any());
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
                new BigDecimal("10.00"), new BigDecimal("113.45"), null, null, null, null, null, null, true, "ACTIVE", null, null, List.of(), null);
    }
}
