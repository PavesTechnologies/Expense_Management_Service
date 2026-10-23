package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.dto.request.InvoiceSyncRequest;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.InvoiceSync;
import com.expense_management_service.mapper.InvoiceSyncMapper;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.InvoiceSyncRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The generic invoice-sync endpoint must not be a way around the handoff queue's eligibility and duplicate checks. */
@ExtendWith(MockitoExtension.class)
class InvoiceSyncServiceImplTest {

    @Mock private InvoiceSyncRepository invoiceSyncRepository;
    @Mock private ExpenseLineItemRepository expenseLineItemRepository;

    private InvoiceSyncServiceImpl service;
    private final UUID lineItemId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new InvoiceSyncServiceImpl(invoiceSyncRepository, expenseLineItemRepository, new InvoiceSyncMapper());
    }

    private InvoiceSyncRequest request(String syncStatus) {
        return new InvoiceSyncRequest(lineItemId, "INV-1", syncStatus, 0, null);
    }

    private InvoiceSync existing(String syncStatus) {
        InvoiceSync sync = InvoiceSync.builder().syncId(UUID.randomUUID())
                .lineItem(ExpenseLineItem.builder().lineItemId(lineItemId).build()).syncStatus(syncStatus).build();
        when(invoiceSyncRepository.findById(sync.getSyncId())).thenReturn(Optional.of(sync));
        return sync;
    }

    @Test
    void create_refusesAHandoffRecord_inAnyCase() {
        assertThatThrownBy(() -> service.create(request("HANDED_OFF")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("invoice handoff queue");
        assertThatThrownBy(() -> service.create(request(" handed_off ")))
                .isInstanceOf(BusinessRuleViolationException.class);
        verify(invoiceSyncRepository, never()).save(any());
    }

    @Test
    void create_allowsOtherStatuses() {
        when(expenseLineItemRepository.findById(lineItemId)).thenReturn(Optional.of(ExpenseLineItem.builder().lineItemId(lineItemId).build()));
        when(invoiceSyncRepository.save(any(InvoiceSync.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.create(request("FAILED")).syncStatus()).isEqualTo("FAILED");
    }

    @Test
    void update_refusesToTurnARecordIntoAHandoff() {
        InvoiceSync sync = existing("FAILED");

        assertThatThrownBy(() -> service.update(sync.getSyncId(), request("HANDED_OFF")))
                .isInstanceOf(BusinessRuleViolationException.class);
        verify(invoiceSyncRepository, never()).save(any());
    }

    @Test
    void update_refusesToEditAHandoffRecord() {
        InvoiceSync sync = existing("HANDED_OFF");

        assertThatThrownBy(() -> service.update(sync.getSyncId(), request("FAILED")))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(sync.getSyncStatus()).isEqualTo("HANDED_OFF");
        verify(invoiceSyncRepository, never()).save(any());
    }

    @Test
    void delete_refusesToDeleteAHandoffRecord() {
        InvoiceSync sync = existing("HANDED_OFF");

        assertThatThrownBy(() -> service.delete(sync.getSyncId()))
                .isInstanceOf(BusinessRuleViolationException.class);
        verify(invoiceSyncRepository, never()).delete(any());
    }

    @Test
    void delete_removesOtherRecords() {
        InvoiceSync sync = existing("FAILED");

        service.delete(sync.getSyncId());

        verify(invoiceSyncRepository).delete(sync);
    }
}
