package com.expense_management_service.service;

import com.expense_management_service.dto.response.CashAdvanceDocumentResponse;
import com.expense_management_service.enums.CashAdvanceDocumentCategory;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/** Supporting documents attached to a cash advance request. */
public interface CashAdvanceDocumentService {

    /** Attaches a document. Only the owner can, and only while the request is a draft. */
    CashAdvanceDocumentResponse upload(UUID advanceId, CashAdvanceDocumentCategory category, MultipartFile file);

    /** The documents still attached. Visible to the owner, the assigned manager, and Finance and Admin. */
    List<CashAdvanceDocumentResponse> list(UUID advanceId);

    /** A short-lived link to view the document in the browser. Same access rules as list. */
    com.expense_management_service.dto.response.ReceiptUrlResponse viewUrl(UUID advanceId, UUID documentId);

    /** A short-lived link that downloads the document under its original file name. Same access rules as list. */
    com.expense_management_service.dto.response.ReceiptUrlResponse downloadUrl(UUID advanceId, UUID documentId);

    /** Removes a document (a soft delete). Only the owner can, and only while the request is a draft. */
    void delete(UUID advanceId, UUID documentId);
}
