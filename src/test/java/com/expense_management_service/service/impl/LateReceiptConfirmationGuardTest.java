package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.dto.request.ReceiptConfirmRequest;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ReceiptOcrRepository;
import com.expense_management_service.repository.ReceiptRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.ExpenseLineItemService;
import com.expense_management_service.service.TaxSnapshotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A late receipt is already attached to its original expense. Confirming it as a new expense, or confirming another
 * receipt onto its expense, must not create or unlink anything.
 */
@ExtendWith(MockitoExtension.class)
class LateReceiptConfirmationGuardTest {

    private static final String EMPLOYEE = "5100014";

    @Mock private ReceiptRepository receiptRepository;
    @Mock private ReceiptOcrRepository receiptOcrRepository;
    @Mock private ExpenseLineItemRepository expenseLineItemRepository;
    @Mock private ExpenseLineItemService expenseLineItemService;
    @Mock private CurrencyRepository currencyRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private TaxSnapshotService taxSnapshotService;
    @Mock private AuditLogRepository auditLogRepository;

    @InjectMocks
    private ReceiptConfirmationServiceImpl service;

    private ExpenseReport report;
    private ExpenseLineItem expense;

    private void asEmployee() {
        when(currentUserService.getCurrentUser()).thenReturn(new CurrentUser(
                UUID.randomUUID(), null, EMPLOYEE, "employee@example.com", "Test User", List.of("GENERAL"), List.of()));
    }

    private void setUpExpense() {
        report = ExpenseReport.builder().reportId(UUID.randomUUID()).employeeId(EMPLOYEE)
                .reportStatus(ReportStatus.APPROVED).build();
        expense = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).report(report)
                .amount(new java.math.BigDecimal("900.00")).build();
    }

    @Test
    void confirm_isRefused_forALateReceipt_andNoLineItemIsCreated() {
        setUpExpense();
        asEmployee();
        Receipt late = Receipt.builder().receiptId(UUID.randomUUID()).report(report).lineItem(expense).employeeId(EMPLOYEE)
                .attachmentKind(Receipt.ATTACHMENT_LATE).build();
        when(receiptRepository.findById(late.getReceiptId())).thenReturn(Optional.of(late));

        ReceiptConfirmRequest request = new ReceiptConfirmRequest(null, UUID.randomUUID(), null, null, null,
                new java.math.BigDecimal("900.00"), UUID.randomUUID(), null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.confirm(late.getReceiptId(), request))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already attached to its expense");
        verify(expenseLineItemService, never()).create(any(), any());
        verify(receiptRepository, never()).save(any(Receipt.class));
    }

    @Test
    void confirmingAnotherReceiptOntoTheExpense_doesNotUnlinkTheLateReceipt() {
        setUpExpense();
        asEmployee();
        Receipt original = Receipt.builder().receiptId(UUID.randomUUID()).report(report).employeeId(EMPLOYEE)
                .attachmentKind(Receipt.ATTACHMENT_ORIGINAL).build();
        Receipt late = Receipt.builder().receiptId(UUID.randomUUID()).report(report).lineItem(expense).employeeId(EMPLOYEE)
                .attachmentKind(Receipt.ATTACHMENT_LATE).build();
        when(receiptRepository.findById(original.getReceiptId())).thenReturn(Optional.of(original));
        when(expenseLineItemRepository.findById(expense.getLineItemId())).thenReturn(Optional.of(expense));
        when(receiptRepository.findByLineItem_LineItemId(expense.getLineItemId())).thenReturn(List.of(late));
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(original.getReceiptId()))
                .thenReturn(Optional.empty());

        ReceiptConfirmRequest request = new ReceiptConfirmRequest(expense.getLineItemId(), null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        service.confirm(original.getReceiptId(), request);

        assertThat(late.getLineItem()).isSameAs(expense);
        assertThat(original.getLineItem()).isSameAs(expense);
        verify(receiptRepository, never()).save(late);
    }
}
