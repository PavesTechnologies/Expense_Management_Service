package com.expense_management_service.service;

import com.expense_management_service.dto.request.ReceiptRequest;
import com.expense_management_service.dto.response.ReceiptResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ReceiptService {

    ReceiptResponse create(ReceiptRequest request);

    ReceiptResponse update(UUID receiptId, ReceiptRequest request);

    ReceiptResponse getById(UUID receiptId);

    Page<ReceiptResponse> getAll(Pageable pageable);

    void delete(UUID receiptId);
}
