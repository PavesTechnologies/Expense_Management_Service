package com.expense_management_service.service.impl;

import java.util.List;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ReceiptRequest;
import com.expense_management_service.dto.response.ReceiptResponse;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.mapper.ReceiptMapper;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ReceiptRepository;
import com.expense_management_service.service.ReceiptService;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ReceiptServiceImpl implements ReceiptService {

    private final ReceiptRepository receiptRepository;
    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final ReceiptMapper receiptMapper;

    @Override
    public ReceiptResponse create(ReceiptRequest request) {
        Receipt entity = receiptMapper.toEntity(request);
        entity.setLineItem(findLineItem(request.lineItemId()));
        entity.setUploadedAt(LocalDateTime.now());
        return receiptMapper.toResponse(receiptRepository.save(entity));
    }

    @Override
    public ReceiptResponse update(UUID receiptId, ReceiptRequest request) {
        Receipt entity = findEntity(receiptId);
        receiptMapper.updateEntity(entity, request);
        entity.setLineItem(findLineItem(request.lineItemId()));
        return receiptMapper.toResponse(receiptRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public ReceiptResponse getById(UUID receiptId) {
        return receiptMapper.toResponse(findEntity(receiptId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReceiptResponse> getAll() {
        return receiptRepository.findAll().stream().map(receiptMapper::toResponse).toList();
    }

    @Override
    public void delete(UUID receiptId) {
        receiptRepository.delete(findEntity(receiptId));
    }

    private ExpenseLineItem findLineItem(UUID lineItemId) {
        return expenseLineItemRepository.findById(lineItemId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseLineItem not found with id: " + lineItemId));
    }

    private Receipt findEntity(UUID receiptId) {
        return receiptRepository.findById(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found with id: " + receiptId));
    }
}
