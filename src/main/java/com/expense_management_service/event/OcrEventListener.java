package com.expense_management_service.event;

import com.expense_management_service.service.OCRService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reacts to {@link ReceiptUploadedEvent} by kicking off OCR — the only class in the codebase
 * that connects "a receipt was uploaded" to "run OCR for it". {@code ReceiptServiceImpl} publishes
 * the event without knowing this listener, or OCR, exist.
 * <p>
 * {@code @TransactionalEventListener(AFTER_COMMIT)} guarantees this never fires for a receipt
 * whose upload transaction ultimately rolled back — OCR only ever starts against a durably saved
 * row. {@code @Async} then hands the actual OCR run to a background thread (see
 * {@code AsyncConfig#ocrTaskExecutor}) so the upload response is never held up waiting on it.
 * {@code fallbackExecution = true} keeps this usable from a caller with no active transaction
 * (e.g. a test) instead of silently dropping the event.
 * <p>
 * This is also the seam Change 5 (queue readiness) asks for: swapping this listener for a Kafka
 * or SQS consumer later is a change to this one class only — {@link OCRService#processReceipt}
 * itself never changes, since it already takes nothing but a receipt id.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OcrEventListener {

    private final OCRService ocrService;

    @Async("ocrTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onReceiptUploaded(ReceiptUploadedEvent event) {
        log.info("[OCR] ReceiptUploadedEvent received — receiptId={}, thread={}",
                event.receiptId(), Thread.currentThread().getName());
        try {
            log.info("[OCR] Invoking OCRService.processReceipt() for receipt {}", event.receiptId());
            ocrService.processReceipt(event.receiptId());
            log.info("[OCR] OCRService.processReceipt() returned normally for receipt {}", event.receiptId());
        } catch (RuntimeException ex) {
            // OCRService.processReceipt already catches everything it can turn into a proper
            // RETRY_AVAILABLE/FAILED outcome — anything that still escapes here means the receipt
            // itself couldn't even be loaded (e.g. ResourceNotFoundException), so there is no
            // Receipt row left to mark. Logged in full so this is never silently invisible.
            log.error("[OCR] Unexpected failure processing OCR for receipt {} (triggered via event) — "
                    + "receipt may be left without an updated status; investigate directly", event.receiptId(), ex);
        } finally {
            log.info("[OCR] Exiting onReceiptUploaded() for receipt {}", event.receiptId());
        }
    }
}
