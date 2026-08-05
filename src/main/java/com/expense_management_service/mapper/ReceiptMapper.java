package com.expense_management_service.mapper;

import com.expense_management_service.dto.response.ReceiptResponse;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.enums.OcrStatus;
import org.springframework.stereotype.Component;

@Component
public class ReceiptMapper {

    public ReceiptResponse toResponse(Receipt entity) {
        return new ReceiptResponse(
                entity.getReceiptId(),
                entity.getReport() != null ? entity.getReport().getReportId() : null,
                entity.getLineItem() != null ? entity.getLineItem().getLineItemId() : null,
                entity.getOriginalFileName(),
                entity.getContentType(),
                entity.getFileSize(),
                entity.getUploadedBy(),
                entity.getUploadedAt(),
                entity.getOcrStatus() != null ? OcrStatus.valueOf(entity.getOcrStatus()) : null
        );
    }
}
