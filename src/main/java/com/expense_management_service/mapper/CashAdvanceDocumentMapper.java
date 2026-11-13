package com.expense_management_service.mapper;

import com.expense_management_service.dto.response.CashAdvanceDocumentResponse;
import com.expense_management_service.entity.CashAdvanceDocument;
import org.springframework.stereotype.Component;

@Component
public class CashAdvanceDocumentMapper {

    public CashAdvanceDocumentResponse toResponse(CashAdvanceDocument document) {
        return new CashAdvanceDocumentResponse(
                document.getDocumentId(),
                document.getAdvance() != null ? document.getAdvance().getAdvanceId() : null,
                document.getCategory(),
                document.getOriginalFileName(),
                document.getContentType(),
                document.getFileSizeBytes(),
                document.getUploadedBy(),
                document.getUploadedAt());
    }
}
