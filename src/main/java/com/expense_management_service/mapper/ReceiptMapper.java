package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.ReceiptRequest;
import com.expense_management_service.dto.response.ReceiptResponse;
import com.expense_management_service.entity.Receipt;
import org.springframework.stereotype.Component;

@Component
public class ReceiptMapper {

    public Receipt toEntity(ReceiptRequest request) {
        return Receipt.builder()
                .fileName(request.fileName())
                .filePath(request.filePath())
                .fileType(request.fileType())
                .fileSize(request.fileSize())
                .uploadedBy(request.uploadedBy())
                .ocrStatus(request.ocrStatus())
                .fileHash(request.fileHash())
                .build();
    }

    public void updateEntity(Receipt entity, ReceiptRequest request) {
        entity.setFileName(request.fileName());
        entity.setFilePath(request.filePath());
        entity.setFileType(request.fileType());
        entity.setFileSize(request.fileSize());
        entity.setUploadedBy(request.uploadedBy());
        entity.setOcrStatus(request.ocrStatus());
        entity.setFileHash(request.fileHash());
    }

    public ReceiptResponse toResponse(Receipt entity) {
        return new ReceiptResponse(
                entity.getReceiptId(),
                entity.getLineItem() != null ? entity.getLineItem().getLineItemId() : null,
                entity.getFileName(),
                entity.getFilePath(),
                entity.getFileType(),
                entity.getFileSize(),
                entity.getUploadedBy(),
                entity.getUploadedAt(),
                entity.getOcrStatus(),
                entity.getFileHash()
        );
    }
}
