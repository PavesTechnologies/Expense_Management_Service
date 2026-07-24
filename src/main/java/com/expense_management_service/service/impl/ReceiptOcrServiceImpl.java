package com.expense_management_service.service.impl;

import java.util.List;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ReceiptOcrRequest;
import com.expense_management_service.dto.response.ReceiptOcrResponse;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.entity.ReceiptOcr;
import com.expense_management_service.mapper.ReceiptOcrMapper;
import com.expense_management_service.repository.ReceiptOcrRepository;
import com.expense_management_service.repository.ReceiptRepository;
import com.expense_management_service.service.ReceiptOcrService;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ReceiptOcrServiceImpl implements ReceiptOcrService {

    private final ReceiptOcrRepository receiptOcrRepository;
    private final ReceiptRepository receiptRepository;
    private final ReceiptOcrMapper receiptOcrMapper;

    @Override
    public ReceiptOcrResponse create(ReceiptOcrRequest request) {
        ReceiptOcr entity = receiptOcrMapper.toEntity(request);
        entity.setReceipt(findReceipt(request.receiptId()));
        entity.setProcessedAt(LocalDateTime.now());
        return receiptOcrMapper.toResponse(receiptOcrRepository.save(entity));
    }

    @Override
    public ReceiptOcrResponse update(UUID ocrId, ReceiptOcrRequest request) {
        ReceiptOcr entity = findEntity(ocrId);
        receiptOcrMapper.updateEntity(entity, request);
        entity.setReceipt(findReceipt(request.receiptId()));
        return receiptOcrMapper.toResponse(receiptOcrRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public ReceiptOcrResponse getById(UUID ocrId) {
        return receiptOcrMapper.toResponse(findEntity(ocrId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReceiptOcrResponse> getAll() {
        return receiptOcrRepository.findAll().stream().map(receiptOcrMapper::toResponse).toList();
    }

    @Override
    public void delete(UUID ocrId) {
        receiptOcrRepository.delete(findEntity(ocrId));
    }

    private Receipt findReceipt(UUID receiptId) {
        return receiptRepository.findById(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found with id: " + receiptId));
    }

    private ReceiptOcr findEntity(UUID ocrId) {
        return receiptOcrRepository.findById(ocrId)
                .orElseThrow(() -> new ResourceNotFoundException("ReceiptOcr not found with id: " + ocrId));
    }
}
