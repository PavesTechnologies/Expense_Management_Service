package com.expense_management_service.service;

import java.util.List;

import com.expense_management_service.dto.request.ReceiptOcrRequest;
import com.expense_management_service.dto.response.ReceiptOcrResponse;


import java.util.UUID;

public interface ReceiptOcrService {

    ReceiptOcrResponse create(ReceiptOcrRequest request);

    ReceiptOcrResponse update(UUID ocrId, ReceiptOcrRequest request);

    ReceiptOcrResponse getById(UUID ocrId);

    List<ReceiptOcrResponse> getAll();

    void delete(UUID ocrId);
}
