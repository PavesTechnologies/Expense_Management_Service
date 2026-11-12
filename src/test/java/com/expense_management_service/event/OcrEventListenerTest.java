package com.expense_management_service.event;

import com.expense_management_service.service.OCRService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain unit test of the listener's delegation logic. What actually makes this run
 * asynchronously after commit is Spring's {@code @Async}/{@code @TransactionalEventListener}
 * machinery (framework behavior, not ours to re-test) — see {@code AsyncConfigTest} for proof
 * that the configured executor genuinely runs work off the calling thread.
 */
@ExtendWith(MockitoExtension.class)
class OcrEventListenerTest {

    @Mock
    private OCRService ocrService;

    private OcrEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new OcrEventListener(ocrService);
    }

    @Test
    void onReceiptUploaded_delegatesToOcrServiceWithTheEventsReceiptId() {
        UUID receiptId = UUID.randomUUID();

        listener.onReceiptUploaded(new ReceiptUploadedEvent(receiptId));

        verify(ocrService).processReceipt(receiptId);
    }

    @Test
    void onReceiptUploaded_swallowsUnexpectedFailure_sinceNoCallerIsWaiting() {
        UUID receiptId = UUID.randomUUID();
        when(ocrService.processReceipt(receiptId)).thenThrow(new RuntimeException("ocr pipeline bug"));

        listener.onReceiptUploaded(new ReceiptUploadedEvent(receiptId));

        verify(ocrService).processReceipt(receiptId);
        // No exception propagates out of the listener — there is nothing left to catch it.
    }
}
