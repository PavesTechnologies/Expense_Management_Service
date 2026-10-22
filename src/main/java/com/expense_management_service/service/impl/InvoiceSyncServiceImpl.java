package com.expense_management_service.service.impl;

import java.util.List;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.InvoiceSyncRequest;
import com.expense_management_service.dto.response.InvoiceSyncResponse;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.InvoiceSync;
import com.expense_management_service.mapper.InvoiceSyncMapper;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.InvoiceSyncRepository;
import com.expense_management_service.service.InvoiceSyncService;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class InvoiceSyncServiceImpl implements InvoiceSyncService {

    /** The status only {@code InvoiceHandoffServiceImpl.markHandedOff} may write - see {@link #assertNotAHandoff}. */
    private static final String STATUS_HANDED_OFF = "HANDED_OFF";

    private final InvoiceSyncRepository invoiceSyncRepository;
    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final InvoiceSyncMapper invoiceSyncMapper;

    @Override
    public InvoiceSyncResponse create(InvoiceSyncRequest request) {
        assertNotAHandoff(request.syncStatus());
        InvoiceSync entity = invoiceSyncMapper.toEntity(request);
        entity.setLineItem(findLineItem(request.lineItemId()));
        entity.setSyncDate(LocalDateTime.now());
        return invoiceSyncMapper.toResponse(invoiceSyncRepository.save(entity));
    }

    @Override
    public InvoiceSyncResponse update(UUID syncId, InvoiceSyncRequest request) {
        InvoiceSync entity = findEntity(syncId);
        assertNotAHandoff(entity.getSyncStatus());
        assertNotAHandoff(request.syncStatus());
        invoiceSyncMapper.updateEntity(entity, request);
        entity.setLineItem(findLineItem(request.lineItemId()));
        return invoiceSyncMapper.toResponse(invoiceSyncRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceSyncResponse getById(UUID syncId) {
        return invoiceSyncMapper.toResponse(findEntity(syncId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceSyncResponse> getAll() {
        return invoiceSyncRepository.findAll().stream().map(invoiceSyncMapper::toResponse).toList();
    }

    @Override
    public void delete(UUID syncId) {
        InvoiceSync entity = findEntity(syncId);
        assertNotAHandoff(entity.getSyncStatus());
        invoiceSyncRepository.delete(entity);
    }

    /**
     * A handoff record is what keeps a line from being billed twice and what marks the report's invoicing done,
     * so it is only ever written by the handoff queue, which checks eligibility, PMS and duplicates first. This
     * generic endpoint can neither create one, edit one, turn another record into one, nor delete one.
     */
    private static void assertNotAHandoff(String syncStatus) {
        if (syncStatus != null && STATUS_HANDED_OFF.equalsIgnoreCase(syncStatus.trim())) {
            throw new BusinessRuleViolationException("Handoff records can only be created through the invoice handoff "
                    + "queue (POST /xms/finance/invoice-handoff-queue/{lineItemId}/handoff), and can't be edited or deleted. "
                    + "To hand a line off again, use the queue's reprocess option with a reason.");
        }
    }

    private ExpenseLineItem findLineItem(UUID lineItemId) {
        return expenseLineItemRepository.findById(lineItemId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseLineItem not found with id: " + lineItemId));
    }

    private InvoiceSync findEntity(UUID syncId) {
        return invoiceSyncRepository.findById(syncId)
                .orElseThrow(() -> new ResourceNotFoundException("InvoiceSync not found with id: " + syncId));
    }
}
