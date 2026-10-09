package com.expense_management_service.repository;

import com.expense_management_service.entity.ReceiptOcr;
import com.expense_management_service.enums.OcrStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReceiptOcrRepository extends JpaRepository<ReceiptOcr, UUID> {

    /** Most recent extraction attempt for a receipt — what the Employee Review Screen and GET /ocr pre-fill from. */
    Optional<ReceiptOcr> findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(UUID receiptId);

    /** Full attempt history for a receipt, newest first — supports retry auditing. */
    List<ReceiptOcr> findByReceipt_ReceiptIdOrderByProcessedAtDesc(UUID receiptId);

    /**
     * Candidate duplicates by vendor + amount + currency + date among completed extractions,
     * excluding the receipt currently being checked. Caller ({@code OCRServiceImpl}) further
     * filters these to the same employee — this method deliberately doesn't join across
     * receipt/lineItem/report/employeeId, keeping the query cheap for what's expected to be a
     * very small candidate set. Currency is compared case-insensitively, same as merchant name —
     * an amount only means the same thing once its currency also matches (100 USD isn't a
     * duplicate of 100 EUR).
     */
    List<ReceiptOcr> findByMerchantNameIgnoreCaseAndAmountAndCurrencyCodeIgnoreCaseAndReceiptDateAndProcessingStatusAndReceipt_ReceiptIdNot(
            String merchantName, BigDecimal amount, String currencyCode, LocalDate receiptDate, OcrStatus processingStatus, UUID excludedReceiptId);
}
