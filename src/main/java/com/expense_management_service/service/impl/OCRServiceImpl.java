package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.ocr.OcrExtractionResult;
import com.expense_management_service.dto.ocr.ParsedReceiptData;
import com.expense_management_service.dto.response.OcrStatusResponse;
import com.expense_management_service.dto.response.ReceiptOcrResponse;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.entity.ReceiptOcr;
import com.expense_management_service.enums.OcrStatus;
import com.expense_management_service.mapper.ReceiptOcrMapper;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.ReceiptOcrRepository;
import com.expense_management_service.repository.ReceiptRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.RoleConstants;
import com.expense_management_service.service.OCRService;
import com.expense_management_service.service.OcrDocumentStrategy;
import com.expense_management_service.service.TextractIntegrationException;
import com.expense_management_service.service.TextractNotApplicableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@link OCRService} implementation. Sequences an ordered {@link OcrDocumentStrategy} chain
 * (Issue 9 — Strategy Pattern: AnalyzeExpense, then AnalyzeDocument for travel documents, then
 * DetectDocumentText as a last resort; see {@link #runStrategies}) → {@link ReceiptOcrRepository},
 * and owns every {@code Receipt.ocrStatus}/{@code ReceiptOcr.processingStatus} transition — see
 * {@link OcrStatus} javadoc for the ownership contract this class is responsible for upholding.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class OCRServiceImpl implements OCRService {

    private static final int FAILURE_REASON_MAX_LENGTH = 500;
    private static final String OCR_ENGINE = "AWS_TEXTRACT";

    private final ReceiptRepository receiptRepository;
    private final ReceiptOcrRepository receiptOcrRepository;
    private final AuditLogRepository auditLogRepository;
    private final List<OcrDocumentStrategy> ocrDocumentStrategies;
    private final ReceiptOcrMapper receiptOcrMapper;
    private final CurrentUserService currentUserService;

    /** Confidence (0.00-1.00) below which extracted fields are flagged for employee review. */
    @Value("${ocr.confidence-threshold:0.80}")
    private BigDecimal confidenceThreshold;

    /**
     * REQUIRES_NEW rather than the class-level default (REQUIRED): this method must never run
     * inside a caller's transaction — most importantly, never inside the receipt upload
     * transaction it's triggered from via {@code OcrEventListener}. REQUIRED would already be
     * safe today (the async listener thread has no ambient transaction to join), but REQUIRES_NEW
     * makes that guarantee structural rather than incidental, so it can't be silently broken by a
     * future caller invoking this from within another transaction.
     * <p>
     * Deliberately performs NO caller-identity check (no {@code assertOwnerOrAdmin}) — this is
     * the entry point {@code OcrEventListener} invokes from a background {@code @Async} thread,
     * which has no {@code SecurityContext} at all (Spring's default thread-local context does
     * not propagate to executor threads). An ownership check here would throw
     * {@code IllegalStateException} on every automatic run and — since the listener only logs
     * and swallows it — silently strand the receipt at {@code UPLOADED} forever, which is exactly
     * the bug this fixes. Ownership is still enforced by every caller that actually has a caller
     * identity: {@link #retryOcr} checks it before ever reaching this method, and the manual
     * force-run controller endpoint is {@code ADMIN}-only at the role level.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReceiptOcrResponse processReceipt(UUID receiptId) {
        Receipt receipt = findReceipt(receiptId);
        log.info("[OCR] Starting OCR for receipt {}", receiptId);

        updateReceiptOcrStatus(receipt, OcrStatus.PROCESSING);
        logAudit(receipt, "OCR_STARTED", null);

        ReceiptOcr attempt = ReceiptOcr.builder()
                .receipt(receipt)
                .processingStatus(OcrStatus.PROCESSING)
                .ocrEngine(OCR_ENGINE)
                .build();

        long startedAtNanos = System.nanoTime();
        try {
            OcrExtractionResult extraction = runStrategies(receiptId, receipt.getObjectKey());
            ParsedReceiptData parsed = extraction.data();
            log.debug("[OCR] Strategy {} parsed receipt {}: merchant={}, total={}, confidence={}",
                    extraction.ocrVersion(), receiptId, parsed.merchantName(), parsed.totalAmount(), parsed.confidenceScore());

            attempt.setOcrVersion(extraction.ocrVersion());
            attempt.setMerchantName(parsed.merchantName());
            attempt.setInvoiceNumber(parsed.invoiceNumber());
            attempt.setReceiptDate(parsed.receiptDate());
            attempt.setReceiptTime(parsed.receiptTime());
            attempt.setCurrencyCode(parsed.currencyCode());
            attempt.setSubtotal(parsed.subtotal());
            attempt.setTaxAmount(parsed.taxAmount());
            attempt.setAmount(parsed.totalAmount());
            attempt.setPaymentMethod(parsed.paymentMethod());
            attempt.setConfidenceScore(parsed.confidenceScore());
            attempt.setProcessingStatus(OcrStatus.OCR_COMPLETED);
            attempt.setProcessedAt(LocalDateTime.now());
            attempt.setProcessingDurationMs(elapsedMillisSince(startedAtNanos));

            ReceiptOcr saved = receiptOcrRepository.save(attempt);
            log.debug("[OCR] ReceiptOcr row saved for receipt {} (ocrId={})", receiptId, saved.getOcrId());

            updateReceiptOcrStatus(receipt, OcrStatus.OCR_COMPLETED);
            logAudit(receipt, "OCR_COMPLETED", "confidenceScore=" + parsed.confidenceScore());

            log.info("[OCR] OCR completed for receipt {} (confidence={})", receiptId, parsed.confidenceScore());
            return toResponseWithFlags(saved, receipt);
        } catch (TextractIntegrationException e) {
            // Deliberately not rethrown — the employee must be able to continue with manual
            // entry when OCR fails. A FAILED result is a normal, successful response from this
            // method's point of view, not an error.
            log.error("[OCR] Textract call failed for receipt {}", receiptId, e);
            return failAttempt(receipt, attempt, startedAtNanos, e.getMessage());
        } catch (RuntimeException e) {
            // Safety net: this method runs unattended on a background thread with nothing else
            // watching it — if ANYTHING else goes wrong (a mapping bug, a DB error, whatever),
            // the receipt must still end up in a terminal, retryable state instead of stuck at
            // PROCESSING forever. Full stack trace logged; nothing here is silently swallowed.
            log.error("[OCR] Unexpected error while processing receipt {} — pipeline stopped unexpectedly", receiptId, e);
            return failAttempt(receipt, attempt, startedAtNanos, "Unexpected OCR error: " + e.getMessage());
        }
    }

    /**
     * Issue 9 (Strategy Pattern): tries each configured {@link OcrDocumentStrategy} in order
     * (AnalyzeExpense, then AnalyzeDocument for travel documents, then DetectDocumentText as a
     * last resort — see each strategy's {@code @Order}) until one reports a meaningful result.
     * The document's type is never decided upfront by this codebase; Textract's own response to
     * each strategy is the signal. A strategy reporting "not applicable" moves to the next one;
     * any other Textract failure (throttling, corruption, network) propagates immediately and
     * ends the chain — see {@link OcrDocumentStrategy} javadoc.
     */
    private OcrExtractionResult runStrategies(UUID receiptId, String objectKey) {
        for (int i = 0; i < ocrDocumentStrategies.size(); i++) {
            OcrDocumentStrategy strategy = ocrDocumentStrategies.get(i);
            boolean isLastStrategy = i == ocrDocumentStrategies.size() - 1;
            try {
                log.debug("[OCR] Trying strategy {} for receipt {}", strategy.ocrVersion(), receiptId);
                OcrExtractionResult result = strategy.extract(objectKey);
                if (result.meaningful() || isLastStrategy) {
                    log.info("[OCR] Using strategy {} for receipt {} (meaningful={})",
                            strategy.ocrVersion(), receiptId, result.meaningful());
                    return result;
                }
                log.debug("[OCR] Strategy {} found nothing meaningful for receipt {} — trying next strategy",
                        strategy.ocrVersion(), receiptId);
            } catch (TextractNotApplicableException e) {
                if (isLastStrategy) {
                    throw e;
                }
                log.debug("[OCR] Strategy {} not applicable to receipt {} — trying next strategy: {}",
                        strategy.ocrVersion(), receiptId, e.getMessage());
            }
        }
        throw new TextractIntegrationException("No OCR document strategy is configured");
    }

    private ReceiptOcrResponse failAttempt(Receipt receipt, ReceiptOcr attempt, long startedAtNanos, String rawMessage) {
        String failureReason = truncateFailureReason(rawMessage);
        attempt.setProcessingStatus(OcrStatus.FAILED);
        attempt.setFailureReason(failureReason);
        attempt.setProcessedAt(LocalDateTime.now());
        attempt.setProcessingDurationMs(elapsedMillisSince(startedAtNanos));

        ReceiptOcr saved = receiptOcrRepository.save(attempt);
        // Receipt-level status goes straight to RETRY_AVAILABLE — the failed attempt itself
        // is already recorded as FAILED on the ReceiptOcr row; there is no separate
        // observable receipt-level FAILED state in between (see OcrStatus javadoc).
        updateReceiptOcrStatus(receipt, OcrStatus.RETRY_AVAILABLE);
        logAudit(receipt, "OCR_FAILED", failureReason);

        log.warn("[OCR] Receipt {} marked RETRY_AVAILABLE — employee can retry or continue manually", receipt.getReceiptId());
        // toResponseWithFlags itself recognizes FAILED and always recommends review for it —
        // no separate flag computation needed here.
        return toResponseWithFlags(saved, receipt);
    }

    @Override
    public ReceiptOcrResponse retryOcr(UUID receiptId) {
        Receipt receipt = findReceipt(receiptId);
        assertOwnerOrAdmin(receipt.getEmployeeId());

        ReceiptOcr latest = receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId).orElse(null);
        if (latest != null && latest.getProcessingStatus() != OcrStatus.FAILED) {
            throw new BusinessRuleViolationException(
                    "OCR can only be retried after a failed attempt — latest attempt status is " + latest.getProcessingStatus());
        }

        log.info("Retrying OCR for receipt {} — reusing the existing S3 object, no re-upload", receiptId);
        return processReceipt(receiptId);
    }

    @Override
    @Transactional(readOnly = true)
    public ReceiptOcrResponse getLatestResult(UUID receiptId) {
        Receipt receipt = findReceipt(receiptId);
        assertViewable(receipt.getEmployeeId());

        ReceiptOcr latest = receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("No OCR result yet for receipt: " + receiptId));

        return toResponseWithFlags(latest, receipt);
    }

    @Override
    @Transactional(readOnly = true)
    public OcrStatusResponse getStatus(UUID receiptId) {
        Receipt receipt = findReceipt(receiptId);
        assertViewable(receipt.getEmployeeId());

        OcrStatus status = receipt.getOcrStatus() != null ? OcrStatus.valueOf(receipt.getOcrStatus()) : null;
        LocalDateTime lastUpdated = receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId)
                .map(ReceiptOcr::getProcessedAt)
                .orElse(receipt.getUploadedAt());

        return new OcrStatusResponse(receiptId, status, lastUpdated);
    }

    @Override
    public void recordOverride(UUID receiptId, String fieldName, String originalValue, String overriddenValue) {
        recordOverride(receiptId, fieldName, originalValue, overriddenValue, null);
    }

    @Override
    public void recordOverride(UUID receiptId, String fieldName, String originalValue, String overriddenValue, String reason) {
        Receipt receipt = findReceipt(receiptId);
        assertOwnerOrAdmin(receipt.getEmployeeId());
        String detail = fieldName + ": '" + originalValue + "' -> '" + overriddenValue + "'"
                + (reason != null && !reason.isBlank() ? " (reason: " + reason + ")" : "");
        logAudit(receipt, "OCR_OVERRIDE", detail);
    }

    private ReceiptOcrResponse toResponseWithFlags(ReceiptOcr ocr, Receipt receipt) {
        boolean possibleDuplicate = ocr.getProcessingStatus() == OcrStatus.OCR_COMPLETED && isDuplicate(ocr, receipt);
        return receiptOcrMapper.toResponse(ocr, possibleDuplicate, isReviewRecommended(ocr));
    }

    /**
     * True whenever the employee should double-check this attempt before relying on it: the
     * extraction failed outright, a core field is missing, confidence is below the configured
     * threshold, or the subtotal/tax/total figures don't reconcile with each other. Advisory
     * only — nothing here blocks anything, it only shapes what the review screen highlights.
     */
    private boolean isReviewRecommended(ReceiptOcr ocr) {
        if (ocr.getProcessingStatus() == OcrStatus.FAILED) {
            return true;
        }
        if (ocr.getReceiptDate() == null || ocr.getMerchantName() == null || ocr.getAmount() == null) {
            return true;
        }
        if (ocr.getConfidenceScore() == null || ocr.getConfidenceScore().compareTo(confidenceThreshold) < 0) {
            return true;
        }
        return isTaxCalculationInconsistent(ocr);
    }

    /** Only checked when both subtotal and tax are present — many receipts have no subtotal line at all, which isn't itself a problem. */
    private boolean isTaxCalculationInconsistent(ReceiptOcr ocr) {
        if (ocr.getSubtotal() == null || ocr.getTaxAmount() == null) {
            return false;
        }
        BigDecimal expectedTotal = ocr.getSubtotal().add(ocr.getTaxAmount());
        BigDecimal tolerance = new BigDecimal("0.05");
        return expectedTotal.subtract(ocr.getAmount()).abs().compareTo(tolerance) > 0;
    }

    /**
     * Vendor + amount + currency + date duplicate detection, scoped to the same employee only —
     * the same vendor/amount/date is common across different employees (e.g. a team lunch) and
     * would otherwise produce constant false positives. Currency is part of the match because an
     * amount alone is meaningless across currencies (100 USD is not a duplicate of 100 EUR).
     * Advisory only: the result only ever sets {@code possibleDuplicate} on the response, it
     * never blocks anything — the employee decides, consistent with this module never
     * auto-acting on the employee's behalf.
     */
    private boolean isDuplicate(ReceiptOcr ocr, Receipt receipt) {
        if (ocr.getMerchantName() == null || ocr.getAmount() == null || ocr.getCurrencyCode() == null || ocr.getReceiptDate() == null) {
            return false;
        }
        return receiptOcrRepository.findByMerchantNameIgnoreCaseAndAmountAndCurrencyCodeIgnoreCaseAndReceiptDateAndProcessingStatusAndReceipt_ReceiptIdNot(
                        ocr.getMerchantName(), ocr.getAmount(), ocr.getCurrencyCode(), ocr.getReceiptDate(), OcrStatus.OCR_COMPLETED, receipt.getReceiptId())
                .stream()
                .anyMatch(candidate -> receipt.getEmployeeId().equals(candidate.getReceipt().getEmployeeId()));
    }

    private void updateReceiptOcrStatus(Receipt receipt, OcrStatus status) {
        receipt.setOcrStatus(status.name());
        receiptRepository.save(receipt);
    }

    private void logAudit(Receipt receipt, String action, String detail) {
        AuditLog auditLog = AuditLog.builder()
                .entityName("Receipt")
                .entityId(receipt.getReceiptId())
                .action(action)
                .newValue(detail)
                .performedBy(resolvePerformedBy())
                .performedAt(LocalDateTime.now())
                .build();
        auditLogRepository.save(auditLog);
    }

    /**
     * {@code processReceipt} runs both synchronously (authenticated HTTP request) and
     * asynchronously (the event listener's background thread, with no {@code SecurityContext}
     * at all) — this must not throw in either case. "SYSTEM" attributes the audit entry to the
     * automated pipeline rather than a specific employee, which is exactly what a
     * background-triggered OCR run is.
     */
    private String resolvePerformedBy() {
        try {
            return currentUserService.getEmployeeId();
        } catch (IllegalStateException ex) {
            return "SYSTEM";
        }
    }

    private long elapsedMillisSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private String truncateFailureReason(String message) {
        String safeMessage = message == null ? "Unknown OCR failure" : message;
        return safeMessage.length() > FAILURE_REASON_MAX_LENGTH ? safeMessage.substring(0, FAILURE_REASON_MAX_LENGTH) : safeMessage;
    }

    private Receipt findReceipt(UUID receiptId) {
        return receiptRepository.findById(receiptId)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found with id: " + receiptId));
    }

    private void assertOwnerOrAdmin(String employeeId) {
        CurrentUser caller = currentUserService.getCurrentUser();
        if (hasRole(caller, RoleConstants.ADMIN)) {
            return;
        }
        if (!employeeId.equals(caller.employeeId())) {
            throw new AccessDeniedException("You can only manage OCR on your own expense report");
        }
    }

    private void assertViewable(String employeeId) {
        CurrentUser caller = currentUserService.getCurrentUser();
        boolean privileged = hasRole(caller, RoleConstants.ADMIN) || hasRole(caller, RoleConstants.FINANCE)
                || hasRole(caller, RoleConstants.MANAGER);
        if (privileged) {
            return;
        }
        if (!employeeId.equals(caller.employeeId())) {
            throw new AccessDeniedException("You can only view OCR results on your own expense report");
        }
    }

    private boolean hasRole(CurrentUser caller, String role) {
        return caller.roles() != null && caller.roles().stream().anyMatch(r -> r.equalsIgnoreCase(role));
    }
}
