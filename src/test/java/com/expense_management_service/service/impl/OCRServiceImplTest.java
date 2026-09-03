package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.ocr.OcrExtractionResult;
import com.expense_management_service.dto.ocr.ParsedReceiptData;
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
import com.expense_management_service.service.OcrDocumentStrategy;
import com.expense_management_service.service.TextractIntegrationException;
import com.expense_management_service.service.TextractNotApplicableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OCRServiceImplTest {

    @Mock
    private ReceiptRepository receiptRepository;
    @Mock
    private ReceiptOcrRepository receiptOcrRepository;
    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private OcrDocumentStrategy ocrDocumentStrategy;
    @Mock
    private CurrentUserService currentUserService;

    private OCRServiceImpl ocrService;

    private final String employeeId = "5100014";
    private UUID receiptId;
    private Receipt receipt;

    @BeforeEach
    void setUp() {
        ocrService = new OCRServiceImpl(receiptRepository, receiptOcrRepository, auditLogRepository,
                List.of(ocrDocumentStrategy), new ReceiptOcrMapper(), currentUserService);
        ReflectionTestUtils.setField(ocrService, "confidenceThreshold", new BigDecimal("0.80"));

        receiptId = UUID.randomUUID();
        receipt = Receipt.builder().receiptId(receiptId).employeeId(employeeId).objectKey("receipts/key").build();

        // lenient: processReceipt() deliberately never calls getCurrentUser() (it runs on the
        // async event-listener thread, which has no SecurityContext at all) — only
        // retryOcr/getLatestResult/getStatus/recordOverride need this stub.
        lenient().when(currentUserService.getCurrentUser())
                .thenReturn(new CurrentUser(UUID.randomUUID(), employeeId, "jordan@example.com", "Jordan", List.of("GENERAL"), List.of()));
        when(receiptRepository.findById(receiptId)).thenReturn(Optional.of(receipt));
        // lenient: not every test reaches a save/duplicate-check call (e.g. the retry-rejected
        // and read-only tests don't), so these are shared "if needed" stubs, not universal ones.
        lenient().when(receiptRepository.save(any(Receipt.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(receiptOcrRepository.save(any(ReceiptOcr.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(receiptOcrRepository.findByMerchantNameIgnoreCaseAndAmountAndCurrencyCodeIgnoreCaseAndReceiptDateAndProcessingStatusAndReceipt_ReceiptIdNot(
                any(), any(), any(), any(), any(), any())).thenReturn(List.of());
    }

    private ParsedReceiptData sampleParsedData() {
        return new ParsedReceiptData("Acme Taxi", "INV-001", LocalDate.of(2026, 1, 15), null, "USD",
                new BigDecimal("100.00"), new BigDecimal("23.45"), new BigDecimal("123.45"), "UPI", new BigDecimal("0.90"));
    }

    private OcrExtractionResult sampleMeaningfulExtraction() {
        return new OcrExtractionResult(sampleParsedData(), true, "AnalyzeExpense");
    }

    @Test
    void processReceipt_success_persistsCompletedResultAndUpdatesReceiptStatus() {
        when(ocrDocumentStrategy.extract("receipts/key")).thenReturn(sampleMeaningfulExtraction());

        ReceiptOcrResponse response = ocrService.processReceipt(receiptId);

        assertThat(response.processingStatus()).isEqualTo(OcrStatus.OCR_COMPLETED);
        assertThat(response.merchantName()).isEqualTo("Acme Taxi");
        assertThat(response.invoiceNumber()).isEqualTo("INV-001");
        assertThat(response.subtotal()).isEqualByComparingTo("100.00");
        assertThat(response.taxAmount()).isEqualByComparingTo("23.45");
        assertThat(response.totalAmount()).isEqualByComparingTo("123.45");
        assertThat(response.paymentMethod()).isEqualTo("UPI");
        assertThat(response.reviewRecommended()).isFalse();
        assertThat(receipt.getOcrStatus()).isEqualTo("OCR_COMPLETED");

        ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(2)).save(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues()).extracting(AuditLog::getAction)
                .containsExactly("OCR_STARTED", "OCR_COMPLETED");
    }

    @Test
    void processReceipt_persistsOcrVersion_fromWhicheverStrategySucceeded() {
        when(ocrDocumentStrategy.extract("receipts/key"))
                .thenReturn(new OcrExtractionResult(sampleParsedData(), true, "AnalyzeDocument"));

        ArgumentCaptor<ReceiptOcr> captor = ArgumentCaptor.forClass(ReceiptOcr.class);
        ocrService.processReceipt(receiptId);

        verify(receiptOcrRepository).save(captor.capture());
        assertThat(captor.getValue().getOcrVersion()).isEqualTo("AnalyzeDocument");
    }

    /**
     * Regression test for the root cause of the "stuck at UPLOADED forever" bug:
     * {@code processReceipt} is invoked from {@code OcrEventListener} on a background
     * {@code @Async} thread that has no {@code SecurityContext} at all. It must never depend on
     * {@code CurrentUserService.getCurrentUser()} — doing so used to throw
     * {@code IllegalStateException} before the status was ever set to PROCESSING, and
     * {@code OcrEventListener} silently swallowed it. This test fails against that old behavior
     * and passes now that the ownership check has been removed from this method entirely.
     */
    @Test
    void processReceipt_neverCallsGetCurrentUser_sinceItRunsOnABackgroundThreadWithNoSecurityContext() {
        when(ocrDocumentStrategy.extract("receipts/key")).thenReturn(sampleMeaningfulExtraction());

        ocrService.processReceipt(receiptId);

        verify(currentUserService, never()).getCurrentUser();
    }

    /**
     * Safety net for Requirement 7: absolutely anything unexpected that escapes the strategy
     * chain — not just a {@code TextractIntegrationException} — must still leave the receipt in
     * a terminal, retryable state instead of stuck at PROCESSING forever.
     */
    @Test
    void processReceipt_marksRetryAvailable_whenAnUnexpectedRuntimeExceptionEscapesParsing() {
        when(ocrDocumentStrategy.extract("receipts/key")).thenThrow(new IllegalStateException("unexpected parser bug"));

        ReceiptOcrResponse response = ocrService.processReceipt(receiptId);

        assertThat(response.processingStatus()).isEqualTo(OcrStatus.FAILED);
        assertThat(response.failureReason()).contains("Unexpected OCR error").contains("unexpected parser bug");
        assertThat(receipt.getOcrStatus()).isEqualTo("RETRY_AVAILABLE");
    }

    @Test
    void logAudit_fallsBackToSystem_whenNoCurrentUserAvailable() {
        when(currentUserService.getEmployeeId()).thenThrow(new IllegalStateException("no security context"));
        when(ocrDocumentStrategy.extract("receipts/key")).thenReturn(sampleMeaningfulExtraction());

        ocrService.processReceipt(receiptId);

        ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(2)).save(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues()).allSatisfy(entry -> assertThat(entry.getPerformedBy()).isEqualTo("SYSTEM"));
    }

    @Test
    void processReceipt_failure_persistsFailedResultAndDoesNotThrow() {
        when(ocrDocumentStrategy.extract("receipts/key")).thenThrow(new TextractIntegrationException("Textract timed out"));

        ReceiptOcrResponse response = ocrService.processReceipt(receiptId);

        assertThat(response.processingStatus()).isEqualTo(OcrStatus.FAILED);
        assertThat(response.failureReason()).isEqualTo("Textract timed out");
        // A failed attempt always recommends review (Requirement 8) and is never flagged a duplicate.
        assertThat(response.reviewRecommended()).isTrue();
        assertThat(response.possibleDuplicate()).isFalse();
        // Receipt-level status goes straight to RETRY_AVAILABLE — see OcrStatus javadoc.
        assertThat(receipt.getOcrStatus()).isEqualTo("RETRY_AVAILABLE");

        ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(2)).save(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues()).extracting(AuditLog::getAction)
                .containsExactly("OCR_STARTED", "OCR_FAILED");
    }

    @Test
    void retryOcr_throwsBusinessRuleViolation_whenLatestAttemptDidNotFail() {
        ReceiptOcr completed = ReceiptOcr.builder().receipt(receipt).processingStatus(OcrStatus.OCR_COMPLETED).build();
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId)).thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> ocrService.retryOcr(receiptId)).isInstanceOf(BusinessRuleViolationException.class);

        verify(ocrDocumentStrategy, never()).extract(any());
    }

    /**
     * Change 3: every retry must create a brand-new ReceiptOcr row, never overwrite a previous
     * attempt. Plays out the exact scenario from the requirements — two failed attempts followed
     * by a successful third — and verifies all three are distinct persisted rows.
     */
    @Test
    void retry_threeAttempts_firstTwoFailThirdSucceeds_eachCreatesNewRow() {
        List<ReceiptOcr> savedAttempts = new ArrayList<>();
        when(receiptOcrRepository.save(any(ReceiptOcr.class))).thenAnswer(inv -> {
            ReceiptOcr saved = inv.getArgument(0);
            savedAttempts.add(saved);
            return saved;
        });
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId))
                .thenAnswer(inv -> savedAttempts.isEmpty() ? Optional.empty() : Optional.of(savedAttempts.get(savedAttempts.size() - 1)));
        when(ocrDocumentStrategy.extract("receipts/key"))
                .thenThrow(new TextractIntegrationException("attempt 1 failed"))
                .thenThrow(new TextractIntegrationException("attempt 2 failed"))
                .thenReturn(sampleMeaningfulExtraction());

        ReceiptOcrResponse attempt1 = ocrService.processReceipt(receiptId);
        ReceiptOcrResponse attempt2 = ocrService.retryOcr(receiptId);
        ReceiptOcrResponse attempt3 = ocrService.retryOcr(receiptId);

        assertThat(attempt1.processingStatus()).isEqualTo(OcrStatus.FAILED);
        assertThat(attempt2.processingStatus()).isEqualTo(OcrStatus.FAILED);
        assertThat(attempt3.processingStatus()).isEqualTo(OcrStatus.OCR_COMPLETED);

        verify(receiptOcrRepository, times(3)).save(any(ReceiptOcr.class));
        assertThat(savedAttempts).hasSize(3);
        assertThat(savedAttempts.get(0)).isNotSameAs(savedAttempts.get(1));
        assertThat(savedAttempts.get(1)).isNotSameAs(savedAttempts.get(2));
        assertThat(savedAttempts.get(0)).isNotSameAs(savedAttempts.get(2));
    }

    @Test
    void retryOcr_reprocesses_usingExistingS3Object_whenLatestAttemptFailed() {
        ReceiptOcr failed = ReceiptOcr.builder().receipt(receipt).processingStatus(OcrStatus.FAILED).build();
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId)).thenReturn(Optional.of(failed));
        when(ocrDocumentStrategy.extract("receipts/key")).thenReturn(sampleMeaningfulExtraction());

        ReceiptOcrResponse response = ocrService.retryOcr(receiptId);

        assertThat(response.processingStatus()).isEqualTo(OcrStatus.OCR_COMPLETED);
        verify(ocrDocumentStrategy, times(1)).extract("receipts/key");
    }

    @Test
    void getLatestResult_flagsPossibleDuplicate_onlyWhenCurrencyAlsoMatches() {
        ReceiptOcr latest = completedOcr("Acme Taxi", "123.45", "USD", LocalDate.of(2026, 1, 15));
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId)).thenReturn(Optional.of(latest));

        ReceiptOcr sameEmployeeSameCurrencyCandidate = otherReceiptCompletedOcr(employeeId);
        when(receiptOcrRepository.findByMerchantNameIgnoreCaseAndAmountAndCurrencyCodeIgnoreCaseAndReceiptDateAndProcessingStatusAndReceipt_ReceiptIdNot(
                eq("Acme Taxi"), eq(new BigDecimal("123.45")), eq("USD"), eq(LocalDate.of(2026, 1, 15)), eq(OcrStatus.OCR_COMPLETED), eq(receiptId)))
                .thenReturn(List.of(sameEmployeeSameCurrencyCandidate));

        ReceiptOcrResponse response = ocrService.getLatestResult(receiptId);

        assertThat(response.possibleDuplicate()).isTrue();
    }

    @Test
    void getLatestResult_doesNotFlagDuplicate_whenCurrencyDiffers() {
        ReceiptOcr latest = completedOcr("Acme Taxi", "123.45", "EUR", LocalDate.of(2026, 1, 15));
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId)).thenReturn(Optional.of(latest));

        ReceiptOcrResponse response = ocrService.getLatestResult(receiptId);

        assertThat(response.possibleDuplicate()).isFalse();
        verify(receiptOcrRepository).findByMerchantNameIgnoreCaseAndAmountAndCurrencyCodeIgnoreCaseAndReceiptDateAndProcessingStatusAndReceipt_ReceiptIdNot(
                "Acme Taxi", new BigDecimal("123.45"), "EUR", LocalDate.of(2026, 1, 15), OcrStatus.OCR_COMPLETED, receiptId);
    }

    @Test
    void getLatestResult_doesNotFlagDuplicate_whenMatchingAttemptBelongsToDifferentEmployee() {
        ReceiptOcr latest = completedOcr("Acme Taxi", "123.45", "USD", LocalDate.of(2026, 1, 15));
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId)).thenReturn(Optional.of(latest));

        ReceiptOcr differentEmployeeCandidate = otherReceiptCompletedOcr("someone-else");
        when(receiptOcrRepository.findByMerchantNameIgnoreCaseAndAmountAndCurrencyCodeIgnoreCaseAndReceiptDateAndProcessingStatusAndReceipt_ReceiptIdNot(
                any(), any(), any(), any(), any(), any())).thenReturn(List.of(differentEmployeeCandidate));

        ReceiptOcrResponse response = ocrService.getLatestResult(receiptId);

        assertThat(response.possibleDuplicate()).isFalse();
    }

    @Test
    void getLatestResult_flagsReviewRecommended_whenConfidenceBelowThreshold() {
        ReceiptOcr lowConfidence = ReceiptOcr.builder().receipt(receipt).processingStatus(OcrStatus.OCR_COMPLETED)
                .merchantName("Acme Taxi").amount(new BigDecimal("123.45")).receiptDate(LocalDate.of(2026, 1, 15))
                .confidenceScore(new BigDecimal("0.50")).build();
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId)).thenReturn(Optional.of(lowConfidence));

        ReceiptOcrResponse response = ocrService.getLatestResult(receiptId);

        assertThat(response.reviewRecommended()).isTrue();
    }

    @Test
    void getLatestResult_flagsReviewRecommended_whenMerchantNameMissing() {
        ReceiptOcr missingMerchant = ReceiptOcr.builder().receipt(receipt).processingStatus(OcrStatus.OCR_COMPLETED)
                .amount(new BigDecimal("123.45")).receiptDate(LocalDate.of(2026, 1, 15)).confidenceScore(new BigDecimal("0.95")).build();
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId)).thenReturn(Optional.of(missingMerchant));

        assertThat(ocrService.getLatestResult(receiptId).reviewRecommended()).isTrue();
    }

    @Test
    void getLatestResult_flagsReviewRecommended_whenReceiptDateMissing() {
        ReceiptOcr missingDate = ReceiptOcr.builder().receipt(receipt).processingStatus(OcrStatus.OCR_COMPLETED)
                .merchantName("Acme Taxi").amount(new BigDecimal("123.45")).confidenceScore(new BigDecimal("0.95")).build();
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId)).thenReturn(Optional.of(missingDate));

        assertThat(ocrService.getLatestResult(receiptId).reviewRecommended()).isTrue();
    }

    @Test
    void getLatestResult_flagsReviewRecommended_whenAmountMissing() {
        ReceiptOcr missingAmount = ReceiptOcr.builder().receipt(receipt).processingStatus(OcrStatus.OCR_COMPLETED)
                .merchantName("Acme Taxi").receiptDate(LocalDate.of(2026, 1, 15)).confidenceScore(new BigDecimal("0.95")).build();
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId)).thenReturn(Optional.of(missingAmount));

        assertThat(ocrService.getLatestResult(receiptId).reviewRecommended()).isTrue();
    }

    @Test
    void getLatestResult_flagsReviewRecommended_whenTaxCalculationInconsistent() {
        ReceiptOcr inconsistent = ReceiptOcr.builder().receipt(receipt).processingStatus(OcrStatus.OCR_COMPLETED)
                .merchantName("Acme Taxi").amount(new BigDecimal("123.45")).currencyCode("USD")
                .receiptDate(LocalDate.of(2026, 1, 15)).confidenceScore(new BigDecimal("0.90"))
                .subtotal(new BigDecimal("100.00")).taxAmount(new BigDecimal("10.00")).build(); // 100+10=110 != 123.45
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId)).thenReturn(Optional.of(inconsistent));

        assertThat(ocrService.getLatestResult(receiptId).reviewRecommended()).isTrue();
    }

    @Test
    void getLatestResult_doesNotFlagReviewRecommended_whenTaxCalculationConsistent() {
        ReceiptOcr consistent = ReceiptOcr.builder().receipt(receipt).processingStatus(OcrStatus.OCR_COMPLETED)
                .merchantName("Acme Taxi").amount(new BigDecimal("123.45")).currencyCode("USD")
                .receiptDate(LocalDate.of(2026, 1, 15)).confidenceScore(new BigDecimal("0.90"))
                .subtotal(new BigDecimal("100.00")).taxAmount(new BigDecimal("23.45")).build(); // 100+23.45=123.45
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId)).thenReturn(Optional.of(consistent));

        assertThat(ocrService.getLatestResult(receiptId).reviewRecommended()).isFalse();
    }

    @Test
    void getLatestResult_doesNotFlagReviewRecommended_whenSubtotalAbsent() {
        // No subtotal line at all — that alone isn't inconsistent, just unavailable.
        ReceiptOcr noSubtotal = completedOcr("Acme Taxi", "123.45", "USD", LocalDate.of(2026, 1, 15));
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId)).thenReturn(Optional.of(noSubtotal));

        assertThat(ocrService.getLatestResult(receiptId).reviewRecommended()).isFalse();
    }

    @Test
    void getLatestResult_throwsResourceNotFound_whenNoAttemptExistsYet() {
        when(receiptOcrRepository.findFirstByReceipt_ReceiptIdOrderByProcessedAtDesc(receiptId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ocrService.getLatestResult(receiptId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getStatus_allowsApExecutive_toViewSomeoneElsesReceipt() {
        when(currentUserService.getCurrentUser()).thenReturn(
                new CurrentUser(UUID.randomUUID(), "ap-user", "ap@example.com", "AP", List.of("AP_EXECUTIVE"), List.of()));

        assertThatCode(() -> ocrService.getStatus(receiptId)).doesNotThrowAnyException();
    }

    @Test
    void getStatus_allowsFinanceExecutive_toViewSomeoneElsesReceipt() {
        when(currentUserService.getCurrentUser()).thenReturn(
                new CurrentUser(UUID.randomUUID(), "finance-user", "finance@example.com", "Finance", List.of("FINANCE_EXECUTIVE"), List.of()));

        assertThatCode(() -> ocrService.getStatus(receiptId)).doesNotThrowAnyException();
    }

    /**
     * "Manual Override" invariant: OCR never overwrites a previously-persisted attempt. Each
     * {@code processReceipt} call builds and saves a brand-new {@code ReceiptOcr} row rather
     * than fetching and mutating an existing one, so nothing the OCR pipeline does can silently
     * clobber a row the employee has already reviewed/edited downstream.
     */
    @Test
    void processReceipt_calledTwice_createsNewAttemptInstanceRatherThanMutatingThePrevious() {
        when(ocrDocumentStrategy.extract("receipts/key")).thenReturn(sampleMeaningfulExtraction());

        ArgumentCaptor<ReceiptOcr> captor = ArgumentCaptor.forClass(ReceiptOcr.class);

        ocrService.processReceipt(receiptId);
        ocrService.processReceipt(receiptId);

        verify(receiptOcrRepository, times(2)).save(captor.capture());
        List<ReceiptOcr> savedAttempts = captor.getAllValues();
        assertThat(savedAttempts.get(0)).isNotSameAs(savedAttempts.get(1));
    }

    @Test
    void recordOverride_logsOcrOverrideAuditEntry() {
        ocrService.recordOverride(receiptId, "amount", "100.00", "120.00");

        ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        AuditLog logged = auditCaptor.getValue();
        assertThat(logged.getAction()).isEqualTo("OCR_OVERRIDE");
        assertThat(logged.getEntityId()).isEqualTo(receiptId);
        assertThat(logged.getNewValue()).contains("amount").contains("100.00").contains("120.00");
    }

    @Test
    void recordOverride_withReason_includesReasonInAuditEntry() {
        ocrService.recordOverride(receiptId, "amount", "100.00", "120.00", "Receipt shows tip separately");

        ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getNewValue()).contains("Receipt shows tip separately");
    }

    private ReceiptOcr completedOcr(String merchantName, String amount, String currencyCode, LocalDate receiptDate) {
        return ReceiptOcr.builder().receipt(receipt).processingStatus(OcrStatus.OCR_COMPLETED)
                .merchantName(merchantName).amount(new BigDecimal(amount)).currencyCode(currencyCode)
                .receiptDate(receiptDate).confidenceScore(new BigDecimal("0.90")).build();
    }

    private ReceiptOcr otherReceiptCompletedOcr(String otherEmployeeId) {
        Receipt otherReceipt = Receipt.builder().receiptId(UUID.randomUUID()).employeeId(otherEmployeeId).build();
        return ReceiptOcr.builder().receipt(otherReceipt).processingStatus(OcrStatus.OCR_COMPLETED).build();
    }

    /**
     * Issue 9 (Strategy Pattern) chain behavior — a separate nested scenario using its own
     * multi-strategy list, since every other test in this class deliberately uses a single
     * strategy to keep the "one Textract call, one parse" mental model of the pre-Issue-9 tests.
     */
    @org.junit.jupiter.api.Nested
    class StrategyChainTest {

        private OCRServiceImpl newService(List<OcrDocumentStrategy> strategies) {
            OCRServiceImpl service = new OCRServiceImpl(receiptRepository, receiptOcrRepository, auditLogRepository,
                    strategies, new ReceiptOcrMapper(), currentUserService);
            ReflectionTestUtils.setField(service, "confidenceThreshold", new BigDecimal("0.80"));
            return service;
        }

        @Test
        void fallsThroughToNextStrategy_whenFirstReportsNotApplicable() {
            OcrDocumentStrategy first = mock(OcrDocumentStrategy.class);
            OcrDocumentStrategy second = mock(OcrDocumentStrategy.class);
            when(first.ocrVersion()).thenReturn("AnalyzeExpense");
            when(first.extract("receipts/key")).thenThrow(new TextractNotApplicableException("not applicable", null));
            when(second.ocrVersion()).thenReturn("AnalyzeDocument");
            when(second.extract("receipts/key")).thenReturn(new OcrExtractionResult(sampleParsedData(), true, "AnalyzeDocument"));

            OCRServiceImpl service = newService(List.of(first, second));

            ReceiptOcrResponse response = service.processReceipt(receiptId);

            assertThat(response.processingStatus()).isEqualTo(OcrStatus.OCR_COMPLETED);
            verify(second).extract("receipts/key");
        }

        @Test
        void fallsThroughToNextStrategy_whenFirstFindsNothingMeaningful() {
            OcrDocumentStrategy first = mock(OcrDocumentStrategy.class);
            OcrDocumentStrategy second = mock(OcrDocumentStrategy.class);
            when(first.ocrVersion()).thenReturn("AnalyzeExpense");
            when(first.extract("receipts/key")).thenReturn(new OcrExtractionResult(
                    new ParsedReceiptData(null, null, null, null, null, null, null, null, null, BigDecimal.ZERO), false, "AnalyzeExpense"));
            when(second.ocrVersion()).thenReturn("AnalyzeDocument");
            when(second.extract("receipts/key")).thenReturn(new OcrExtractionResult(sampleParsedData(), true, "AnalyzeDocument"));

            OCRServiceImpl service = newService(List.of(first, second));

            ReceiptOcrResponse response = service.processReceipt(receiptId);

            assertThat(response.processingStatus()).isEqualTo(OcrStatus.OCR_COMPLETED);
            assertThat(response.merchantName()).isEqualTo("Acme Taxi");
        }

        @Test
        void doesNotTryNextStrategy_whenFirstThrowsAGenuineFailure() {
            OcrDocumentStrategy first = mock(OcrDocumentStrategy.class);
            OcrDocumentStrategy second = mock(OcrDocumentStrategy.class);
            when(first.ocrVersion()).thenReturn("AnalyzeExpense");
            when(first.extract("receipts/key")).thenThrow(new TextractIntegrationException("AWS Textract is temporarily rate-limited"));

            OCRServiceImpl service = newService(List.of(first, second));

            ReceiptOcrResponse response = service.processReceipt(receiptId);

            assertThat(response.processingStatus()).isEqualTo(OcrStatus.FAILED);
            assertThat(response.failureReason()).contains("temporarily rate-limited");
            verify(second, never()).extract(any());
        }

        @Test
        void acceptsLastStrategysResult_evenWhenNotMeaningful_sinceThereIsNoFurtherFallback() {
            OcrDocumentStrategy first = mock(OcrDocumentStrategy.class);
            OcrDocumentStrategy last = mock(OcrDocumentStrategy.class);
            ParsedReceiptData sparse = new ParsedReceiptData(null, null, null, null, "INR", null, null, null, null, new BigDecimal("0.40"));
            when(first.ocrVersion()).thenReturn("AnalyzeExpense");
            when(first.extract("receipts/key")).thenThrow(new TextractNotApplicableException("not applicable", null));
            when(last.ocrVersion()).thenReturn("DetectDocumentText");
            when(last.extract("receipts/key")).thenReturn(new OcrExtractionResult(sparse, false, "DetectDocumentText"));

            OCRServiceImpl service = newService(List.of(first, last));

            ReceiptOcrResponse response = service.processReceipt(receiptId);

            assertThat(response.processingStatus()).isEqualTo(OcrStatus.OCR_COMPLETED);
            assertThat(response.currencyCode()).isEqualTo("INR");
            // Sparse data (no date/merchant/amount) — the review-recommendation logic must still
            // catch this even though the strategy chain "succeeded".
            assertThat(response.reviewRecommended()).isTrue();
        }
    }
}
