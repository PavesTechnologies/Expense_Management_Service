package com.expense_management_service.service.impl;

import com.expense_management_service.dto.ocr.FieldConfidence;
import com.expense_management_service.dto.ocr.ParsedReceiptData;
import com.expense_management_service.service.TextractResponseParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.textract.model.AnalyzeExpenseResponse;
import software.amazon.awssdk.services.textract.model.ExpenseDocument;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * {@link TextractResponseParser} implementation for AWS Textract's {@code AnalyzeExpense}.
 * <p>
 * A thin orchestrator (Task 14/SOLID): all actual field-selection strategy lives in one
 * single-responsibility, independently-testable extractor per concern —
 * {@link MerchantExtractor}, {@link DateExtractor}, {@link TimeExtractor},
 * {@link CurrencyExtractor}, {@link TaxExtractor}, {@link AmountExtractor},
 * {@link InvoiceNumberExtractor}, {@link PaymentMethodExtractor}. This class only builds the
 * shared {@link ExpenseFieldIndex} once, calls each extractor, and assembles their results into
 * {@link ParsedReceiptData} plus a {@link FieldConfidence} breakdown (Task 10). Extractors have no
 * dependencies of their own (pure functions over an {@link ExpenseFieldIndex}), so they're plain
 * fields here rather than Spring-managed beans.
 * <p>
 * English-language receipts only — no locale/language detection is attempted. Multi-page PDFs are
 * supported the same way Textract itself supports them: {@code AnalyzeExpense} already treats a
 * multi-page PDF as one logical expense document, so only the first {@link ExpenseDocument} in
 * the response is read; page count has no bearing on parsing itself (that's handled entirely at
 * the Textract-call layer — see {@code TextractServiceImpl}).
 * <p>
 * Deliberately excludes line-item-level detail (products, SKUs, quantities, unit prices) — one
 * receipt maps to one expense line item in this system (we reimburse the total, not individual
 * purchases), so {@code document.lineItemGroups()} is never read.
 * <p>
 * Deliberately tolerant of missing or malformed individual fields — a receipt with an unparseable
 * date or a missing tax line still produces a usable {@link ParsedReceiptData} with those fields
 * left {@code null}, rather than failing the whole extraction.
 */
@Component
@Slf4j
public class TextractResponseParserImpl implements TextractResponseParser {

    private final MerchantExtractor merchantExtractor = new MerchantExtractor();
    private final InvoiceNumberExtractor invoiceNumberExtractor = new InvoiceNumberExtractor();
    private final DateExtractor dateExtractor = new DateExtractor();
    private final TimeExtractor timeExtractor = new TimeExtractor();
    private final CurrencyExtractor currencyExtractor = new CurrencyExtractor();
    private final TaxExtractor taxExtractor = new TaxExtractor();
    private final AmountExtractor amountExtractor = new AmountExtractor();
    private final PaymentMethodExtractor paymentMethodExtractor = new PaymentMethodExtractor();

    @Override
    public ParsedReceiptData parse(AnalyzeExpenseResponse rawResponse) {
        if (rawResponse == null || rawResponse.expenseDocuments() == null || rawResponse.expenseDocuments().isEmpty()) {
            log.warn("Textract returned no expense documents to parse");
            return new ParsedReceiptData(null, null, null, null, null, null, null, null, null, BigDecimal.ZERO);
        }

        ExpenseDocument document = rawResponse.expenseDocuments().get(0);
        ExpenseFieldIndex index = ExpenseFieldIndex.from(document);

        ExtractionResult<String> merchant = merchantExtractor.extract(index);
        ExtractionResult<String> invoiceNumber = invoiceNumberExtractor.extract(index);
        ExtractionResult<LocalDate> receiptDate = dateExtractor.extract(index);
        ExtractionResult<LocalTime> receiptTime = timeExtractor.extract(index);
        ExtractionResult<BigDecimal> subtotal = amountExtractor.extractSubtotal(index);
        ExtractionResult<BigDecimal> taxAmount = taxExtractor.extract(index);
        ExtractionResult<BigDecimal> totalAmount = amountExtractor.extractTotal(index, subtotal.value(), taxAmount.value());
        ExtractionResult<String> currencyCode = currencyExtractor.extract(index);
        ExtractionResult<String> paymentMethod = paymentMethodExtractor.extract(index);

        FieldConfidence fieldConfidence = new FieldConfidence(
                merchant.confidence(), receiptDate.confidence(), totalAmount.confidence(),
                taxAmount.confidence(), currencyCode.confidence());
        BigDecimal overallConfidence = aggregateConfidence(fieldConfidence);

        log.debug("[OCR] Parsed receipt: merchant={}, date={}, total={}, currency={}, overallConfidence={}",
                merchant.value(), receiptDate.value(), totalAmount.value(), currencyCode.value(), overallConfidence);

        return new ParsedReceiptData(
                merchant.value(), invoiceNumber.value(), receiptDate.value(), receiptTime.value(), currencyCode.value(),
                subtotal.value(), taxAmount.value(), totalAmount.value(), paymentMethod.value(),
                overallConfidence, fieldConfidence);
    }

    /**
     * Task 10: overall confidence is the average of whichever per-field confidences were actually
     * computed (a field that was never found contributes nothing, rather than dragging the
     * average toward zero) — the same "average of what's present" principle the original
     * single-number confidence calculation used, just now over the five named fields in
     * {@link FieldConfidence} instead of four arbitrarily-chosen raw Textract field confidences.
     */
    private BigDecimal aggregateConfidence(FieldConfidence fieldConfidence) {
        // Stream.of (unlike List.of) tolerates the null elements that are the normal case here —
        // null means that field was never found at all, not zero confidence.
        List<BigDecimal> present = Stream.of(
                        fieldConfidence.merchantConfidence(), fieldConfidence.dateConfidence(),
                        fieldConfidence.amountConfidence(), fieldConfidence.taxConfidence(),
                        fieldConfidence.currencyConfidence())
                .filter(Objects::nonNull)
                .toList();
        if (present.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = present.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(present.size()), 4, RoundingMode.HALF_UP);
    }
}
