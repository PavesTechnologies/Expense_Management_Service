package com.expense_management_service.service;

import java.util.List;

import com.expense_management_service.dto.request.ReceiptRequest;
import com.expense_management_service.dto.response.ReceiptResponse;


import java.util.UUID;

public interface ReceiptService {

    ReceiptResponse create(ReceiptRequest request);

    ReceiptResponse update(UUID receiptId, ReceiptRequest request);

    ReceiptResponse getById(UUID receiptId);

    List<ReceiptResponse> getAll();

    void delete(UUID receiptId);
}
