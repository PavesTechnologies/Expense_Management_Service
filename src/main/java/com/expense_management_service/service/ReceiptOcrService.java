package com.expense_management_service.service;

import com.expense_management_service.dto.request.ReceiptOcrRequest;
import com.expense_management_service.dto.response.ReceiptOcrResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ReceiptOcrService {

    ReceiptOcrResponse create(ReceiptOcrRequest request);

    ReceiptOcrResponse update(UUID ocrId, ReceiptOcrRequest request);

    ReceiptOcrResponse getById(UUID ocrId);

    Page<ReceiptOcrResponse> getAll(Pageable pageable);

    void delete(UUID ocrId);
}
