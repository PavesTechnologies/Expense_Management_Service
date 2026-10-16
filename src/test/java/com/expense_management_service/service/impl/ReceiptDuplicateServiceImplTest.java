package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.Receipt;
import com.expense_management_service.entity.ReceiptOcr;
import com.expense_management_service.enums.DuplicateReceiptStatus;
import com.expense_management_service.enums.OcrStatus;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.repository.ReceiptOcrRepository;
import com.expense_management_service.repository.ReceiptRepository;
import com.expense_management_service.service.ReceiptDuplicateService.DuplicateMatch;
import com.expense_management_service.service.ReceiptDuplicateService.MatchReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Same bill claimed twice: same employee on two reports, twice in one report, and two employees
 * who shared a bill (identical file, or each photographing it).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReceiptDuplicateServiceImplTest {

    private static final LocalDate BILL_DATE = LocalDate.of(2026, 10, 10);

    @Mock
    private ReceiptRepository receiptRepository;
    @Mock
    private ReceiptOcrRepository receiptOcrRepository;

    private ReceiptDuplicateServiceImpl service;

    private ExpenseReport myDraft;
    private Receipt mine;

    @BeforeEach
    void setUp() {
        service = new ReceiptDuplicateServiceImpl(receiptRepository, receiptOcrRepository);
        myDraft = report("alice", ReportStatus.DRAFT, "ER-0100");
        mine = receipt(myDraft, "alice", "hash-A", true);
        when(receiptRepository.findByFileHash(anyString())).thenReturn(List.of());
        when(receiptOcrRepository.findByReceipt_ReceiptIdOrderByProcessedAtDesc(any())).thenReturn(List.of());
        when(receiptOcrRepository.findCompletedByNormalizedInvoiceNumber(any(), anyString(), any())).thenReturn(List.of());
        when(receiptOcrRepository
                .findByMerchantNameIgnoreCaseAndAmountAndCurrencyCodeIgnoreCaseAndReceiptDateAndProcessingStatusAndReceipt_ReceiptIdNot(
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
    }

    // --- same file ---------------------------------------------------------------------------

    @Test
    void sameFile_onAnotherEmployeesSubmittedReport_blocks_withoutNamingThem() {
        Receipt bobs = receipt(report("bob", ReportStatus.PENDING_APPROVAL, "ER-0042"), "bob", "hash-A", true);
        when(receiptRepository.findByFileHash("hash-A")).thenReturn(List.of(mine, bobs));

        List<DuplicateMatch> matches = service.findMatches(mine);

        assertThat(matches).singleElement().satisfies(m -> {
            assertThat(m.reason()).isEqualTo(MatchReason.SAME_FILE);
            assertThat(m.blocking()).isTrue();
            assertThat(m.sameEmployee()).isFalse();
            assertThat(m.message()).contains("another employee's expense report").doesNotContain("ER-0042");
        });
        assertThat(service.summarize(mine).status()).isEqualTo(DuplicateReceiptStatus.BLOCKING);
    }

    @Test
    void sameFile_onMyOwnEarlierReport_blocks_andNamesThatReport() {
        Receipt earlier = receipt(report("alice", ReportStatus.REIMBURSED, "ER-0007"), "alice", "hash-A", true);
        when(receiptRepository.findByFileHash("hash-A")).thenReturn(List.of(earlier));

        assertThat(service.findMatches(mine)).singleElement()
                .satisfies(m -> assertThat(m.message()).contains("your expense report ER-0007"));
    }

    @Test
    void sameFile_onDraftRejectedOrCancelledReports_isNotAClaim() {
        when(receiptRepository.findByFileHash("hash-A")).thenReturn(List.of(
                receipt(report("bob", ReportStatus.DRAFT, "ER-1"), "bob", "hash-A", true),
                receipt(report("bob", ReportStatus.REJECTED, "ER-2"), "bob", "hash-A", true),
                receipt(report("bob", ReportStatus.CANCELLED, "ER-3"), "bob", "hash-A", true)));

        assertThat(service.findMatches(mine)).isEmpty();
        assertThat(service.summarize(mine)).isEqualTo(com.expense_management_service.service.ReceiptDuplicateService.DuplicateSummary.NONE);
    }

    @Test
    void sameFile_notAttachedToAnyExpense_isNotAClaim() {
        // An abandoned AI scan on a submitted report never became an expense.
        Receipt unattached = receipt(report("bob", ReportStatus.APPROVED, "ER-9"), "bob", "hash-A", false);
        when(receiptRepository.findByFileHash("hash-A")).thenReturn(List.of(unattached));

        assertThat(service.findMatches(mine)).isEmpty();
    }

    @Test
    void sameFile_onAnotherExpenseInTheSameDraftReport_blocks() {
        Receipt sameReportOtherLine = receipt(myDraft, "alice", "hash-A", true);
        when(receiptRepository.findByFileHash("hash-A")).thenReturn(List.of(sameReportOtherLine));

        assertThat(service.findMatches(mine)).singleElement().satisfies(m -> {
            assertThat(m.sameReport()).isTrue();
            assertThat(m.message()).contains("another expense in this report");
        });
    }

    @Test
    void sameFile_attachedTwiceToTheSameExpense_isNotADoubleClaim() {
        Receipt sameLine = receipt(myDraft, "alice", "hash-A", false);
        sameLine.setLineItem(mine.getLineItem());
        when(receiptRepository.findByFileHash("hash-A")).thenReturn(List.of(sameLine));

        assertThat(service.findMatches(mine)).isEmpty();
    }

    // --- shared bill, photographed by each attendee (different files) -------------------------

    @Test
    void sameInvoiceAndGstin_fromAnotherEmployeesPhoto_blocks() {
        ocr(mine, "Spice Route", "inv 2231", "29ABCDE1234F1Z5", "500.00");
        Receipt bobsPhoto = receipt(report("bob", ReportStatus.PENDING_APPROVAL, "ER-0042"), "bob", "hash-B", true);
        ReceiptOcr bobsOcr = ocrEntity(bobsPhoto, "SPICE ROUTE RESTAURANT", "INV2231", "29abcde1234f1z5", "500.00");
        when(receiptOcrRepository.findCompletedByNormalizedInvoiceNumber(OcrStatus.OCR_COMPLETED, "INV2231", mine.getReceiptId()))
                .thenReturn(List.of(bobsOcr));

        assertThat(service.findMatches(mine)).singleElement().satisfies(m -> {
            assertThat(m.reason()).isEqualTo(MatchReason.SAME_INVOICE);
            assertThat(m.blocking()).isTrue();
        });
    }

    @Test
    void sameInvoiceNumber_onADifferentDay_isNotTheSameBill() {
        // Many restaurants restart bill numbers daily.
        ocr(mine, "Spice Route", "INV2231", "29ABCDE1234F1Z5", "500.00");
        Receipt other = receipt(report("bob", ReportStatus.APPROVED, "ER-0042"), "bob", "hash-B", true);
        ReceiptOcr otherOcr = ocrEntity(other, "Spice Route", "INV2231", "29ABCDE1234F1Z5", "500.00");
        otherOcr.setReceiptDate(BILL_DATE.plusDays(1));
        when(receiptOcrRepository.findCompletedByNormalizedInvoiceNumber(any(), eq("INV2231"), any())).thenReturn(List.of(otherOcr));

        assertThat(service.findMatches(mine)).isEmpty();
    }

    @Test
    void sameInvoiceNumber_fromADifferentSupplier_isNotTheSameBill() {
        ocr(mine, "Spice Route", "INV2231", "29ABCDE1234F1Z5", "500.00");
        Receipt other = receipt(report("bob", ReportStatus.APPROVED, "ER-0042"), "bob", "hash-B", true);
        ReceiptOcr otherOcr = ocrEntity(other, "Spice Route", "INV2231", "27ZZZZZ9999Z1Z9", "500.00");
        when(receiptOcrRepository.findCompletedByNormalizedInvoiceNumber(any(), eq("INV2231"), any())).thenReturn(List.of(otherOcr));

        assertThat(service.findMatches(mine)).isEmpty();
    }

    @Test
    void sameMerchantAmountAndDate_withoutInvoiceMatch_isAWarningOnly() {
        ReceiptOcr myOcr = ocr(mine, "Blue Tokai", null, null, "150.00");
        Receipt other = receipt(report("bob", ReportStatus.PENDING_APPROVAL, "ER-0042"), "bob", "hash-B", true);
        ReceiptOcr otherOcr = ocrEntity(other, "Blue Tokai", null, null, "150.00");
        when(receiptOcrRepository
                .findByMerchantNameIgnoreCaseAndAmountAndCurrencyCodeIgnoreCaseAndReceiptDateAndProcessingStatusAndReceipt_ReceiptIdNot(
                        myOcr.getMerchantName(), myOcr.getAmount(), "INR", BILL_DATE, OcrStatus.OCR_COMPLETED, mine.getReceiptId()))
                .thenReturn(List.of(otherOcr));

        assertThat(service.findMatches(mine)).singleElement().satisfies(m -> {
            assertThat(m.reason()).isEqualTo(MatchReason.SAME_DETAILS);
            assertThat(m.blocking()).isFalse();
            assertThat(m.message()).contains("explain it before submitting");
        });
        assertThat(service.summarize(mine).status()).isEqualTo(DuplicateReceiptStatus.WARNING);
    }

    @Test
    void aReceiptMatchingSeveralWays_keepsTheStrongestReason() {
        Receipt bobs = receipt(report("bob", ReportStatus.APPROVED, "ER-0042"), "bob", "hash-A", true);
        when(receiptRepository.findByFileHash("hash-A")).thenReturn(List.of(bobs));
        ReceiptOcr myOcr = ocr(mine, "Blue Tokai", null, null, "150.00");
        when(receiptOcrRepository
                .findByMerchantNameIgnoreCaseAndAmountAndCurrencyCodeIgnoreCaseAndReceiptDateAndProcessingStatusAndReceipt_ReceiptIdNot(
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(ocrEntity(bobs, "Blue Tokai", null, null, "150.00")));

        assertThat(service.findMatches(mine)).singleElement()
                .satisfies(m -> assertThat(m.reason()).isEqualTo(MatchReason.SAME_FILE));
        assertThat(myOcr).isNotNull();
    }

    // --- report / line item views ------------------------------------------------------------

    @Test
    void findBlockingMatchesForReport_ignoresReceiptsNotAttachedToAnExpense() {
        Receipt orphan = receipt(myDraft, "alice", "hash-A", false);
        Receipt bobs = receipt(report("bob", ReportStatus.APPROVED, "ER-0042"), "bob", "hash-A", true);
        when(receiptRepository.findByReport_ReportId(myDraft.getReportId())).thenReturn(List.of(orphan));
        when(receiptRepository.findByFileHash("hash-A")).thenReturn(List.of(bobs));

        assertThat(service.findBlockingMatchesForReport(myDraft)).isEmpty();
    }

    @Test
    void findBlockingMatchesForReport_returnsTheClaimedDuplicate() {
        Receipt bobs = receipt(report("bob", ReportStatus.APPROVED, "ER-0042"), "bob", "hash-A", true);
        when(receiptRepository.findByReport_ReportId(myDraft.getReportId())).thenReturn(List.of(mine));
        when(receiptRepository.findByFileHash("hash-A")).thenReturn(List.of(bobs));

        assertThat(service.findBlockingMatchesForReport(myDraft)).singleElement()
                .satisfies(m -> assertThat(m.otherReceiptId()).isEqualTo(bobs.getReceiptId()));
    }

    @Test
    void findWarningMatchesForLineItem_skipsReceiptsThatAreAlreadyBlocked() {
        Receipt bobs = receipt(report("bob", ReportStatus.APPROVED, "ER-0042"), "bob", "hash-A", true);
        when(receiptRepository.findByLineItem_LineItemId(mine.getLineItem().getLineItemId())).thenReturn(List.of(mine));
        when(receiptRepository.findByFileHash("hash-A")).thenReturn(List.of(bobs));
        ocr(mine, "Blue Tokai", null, null, "150.00");
        when(receiptOcrRepository
                .findByMerchantNameIgnoreCaseAndAmountAndCurrencyCodeIgnoreCaseAndReceiptDateAndProcessingStatusAndReceipt_ReceiptIdNot(
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(ocrEntity(receipt(report("carol", ReportStatus.APPROVED, "ER-5"), "carol", "hash-C", true),
                        "Blue Tokai", null, null, "150.00")));

        assertThat(service.findWarningMatchesForLineItem(mine.getLineItem().getLineItemId())).isEmpty();
    }

    @Test
    void normalizeInvoiceNumber_ignoresCaseAndSpaces_andRejectsTooShortNumbers() {
        assertThat(ReceiptDuplicateServiceImpl.normalizeInvoiceNumber(" inv 22 31 ")).isEqualTo("INV2231");
        assertThat(ReceiptDuplicateServiceImpl.normalizeInvoiceNumber("12")).isNull();
        assertThat(ReceiptDuplicateServiceImpl.normalizeInvoiceNumber(null)).isNull();
    }

    // --- builders ------------------------------------------------------------------------------

    private static ExpenseReport report(String employeeId, ReportStatus status, String number) {
        return ExpenseReport.builder().reportId(UUID.randomUUID()).employeeId(employeeId)
                .reportStatus(status).reportNumber(number).build();
    }

    private static Receipt receipt(ExpenseReport report, String employeeId, String hash, boolean attached) {
        return Receipt.builder()
                .receiptId(UUID.randomUUID())
                .report(report)
                .employeeId(employeeId)
                .originalFileName("bill.jpg")
                .fileHash(hash)
                .lineItem(attached ? ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).report(report).build() : null)
                .build();
    }

    private ReceiptOcr ocr(Receipt receipt, String merchant, String invoice, String gstin, String amount) {
        ReceiptOcr ocr = ocrEntity(receipt, merchant, invoice, gstin, amount);
        when(receiptOcrRepository.findByReceipt_ReceiptIdOrderByProcessedAtDesc(receipt.getReceiptId())).thenReturn(List.of(ocr));
        return ocr;
    }

    private static ReceiptOcr ocrEntity(Receipt receipt, String merchant, String invoice, String gstin, String amount) {
        return ReceiptOcr.builder()
                .ocrId(UUID.randomUUID())
                .receipt(receipt)
                .merchantName(merchant)
                .invoiceNumber(invoice)
                .supplierGstin(gstin)
                .amount(new BigDecimal(amount))
                .currencyCode("INR")
                .receiptDate(BILL_DATE)
                .processingStatus(OcrStatus.OCR_COMPLETED)
                .processedAt(LocalDateTime.now())
                .build();
    }
}
