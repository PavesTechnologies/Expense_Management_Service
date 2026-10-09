package com.expense_management_service.service.impl;

import com.expense_management_service.dto.ocr.ParsedReceiptData;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.textract.model.AnalyzeExpenseResponse;
import software.amazon.awssdk.services.textract.model.ExpenseCurrency;
import software.amazon.awssdk.services.textract.model.ExpenseDetection;
import software.amazon.awssdk.services.textract.model.ExpenseDocument;
import software.amazon.awssdk.services.textract.model.ExpenseField;
import software.amazon.awssdk.services.textract.model.ExpenseType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextractResponseParserImplTest {

    private final TextractResponseParserImpl parser = new TextractResponseParserImpl();

    @Test
    void parse_extractsAllFields_whenTextractFindsEverything() {
        AnalyzeExpenseResponse response = responseWith(
                typedField("VENDOR_NAME", "Acme Taxi", 80.0f, null),
                typedField("INVOICE_RECEIPT_DATE", "01/15/2026", 80.0f, null),
                typedField("INVOICE_RECEIPT_ID", "INV-001", 80.0f, null),
                typedField("SUBTOTAL", "100.00", 80.0f, null),
                typedField("TOTAL", "123.45", 80.0f, "USD"),
                typedField("TAX", "23.45", 80.0f, null)
        );

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.merchantName()).isEqualTo("Acme Taxi");
        assertThat(result.invoiceNumber()).isEqualTo("INV-001");
        assertThat(result.receiptDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(result.receiptTime()).isNull();
        assertThat(result.subtotal()).isEqualByComparingTo("100.00");
        assertThat(result.totalAmount()).isEqualByComparingTo("123.45");
        assertThat(result.taxAmount()).isEqualByComparingTo("23.45");
        assertThat(result.currencyCode()).isEqualTo("USD");
        // Task 10: overall confidence is now the average of the 5 named FieldConfidence
        // components (merchant/date/amount/tax/currency), not 4 arbitrarily-chosen raw Textract
        // field confidences. Currency here comes from Textract's structured currency() sub-field,
        // which CurrencyExtractor scores at a fixed 0.95 rather than reusing the TOTAL field's own
        // 0.80 — so (0.80 + 0.80 + 0.80 + 0.80 + 0.95) / 5 = 0.83, not 0.80.
        assertThat(result.confidenceScore()).isEqualByComparingTo("0.8300");
        assertThat(result.fieldConfidence().merchantConfidence()).isEqualByComparingTo("0.8000");
        assertThat(result.fieldConfidence().dateConfidence()).isEqualByComparingTo("0.8000");
        assertThat(result.fieldConfidence().amountConfidence()).isEqualByComparingTo("0.8000");
        assertThat(result.fieldConfidence().taxConfidence()).isEqualByComparingTo("0.8000");
        assertThat(result.fieldConfidence().currencyConfidence()).isEqualByComparingTo("0.9500");
    }

    @Test
    void parse_leavesFieldsNull_whenTextractFindsNothing() {
        AnalyzeExpenseResponse response = responseWith();

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.merchantName()).isNull();
        assertThat(result.invoiceNumber()).isNull();
        assertThat(result.receiptDate()).isNull();
        assertThat(result.receiptTime()).isNull();
        assertThat(result.subtotal()).isNull();
        assertThat(result.totalAmount()).isNull();
        assertThat(result.taxAmount()).isNull();
        assertThat(result.currencyCode()).isNull();
        assertThat(result.paymentMethod()).isNull();
        assertThat(result.confidenceScore()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void parse_returnsAllNull_whenNoExpenseDocuments() {
        AnalyzeExpenseResponse response = AnalyzeExpenseResponse.builder().expenseDocuments(List.of()).build();

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.merchantName()).isNull();
        assertThat(result.confidenceScore()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void parse_leavesTotalAmountNull_whenUnparseable() {
        AnalyzeExpenseResponse response = responseWith(typedField("TOTAL", "not-a-number", 80.0f, null));

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.totalAmount()).isNull();
    }

    @Test
    void parse_leavesDateNull_whenDateFormatIsUnrecognized() {
        AnalyzeExpenseResponse response = responseWith(typedField("INVOICE_RECEIPT_DATE", "not-a-date", 80.0f, null));

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.receiptDate()).isNull();
    }

    @Test
    void parse_fallsBackToRecieptDateType_whenInvoiceReceiptDateAbsent() {
        AnalyzeExpenseResponse response = responseWith(typedField("RECEIPT_DATE", "2026-08-03", 80.0f, null));

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.receiptDate()).isEqualTo(LocalDate.of(2026, 8, 3));
    }

    @Test
    void parse_extractsEmbeddedTime_fromDateFieldText() {
        // Day value (15) unambiguously forces day-first parsing regardless of day/month order
        // preference — see the day-first-vs-month-first note on ReceiptFieldParsingUtils.DATE_FORMATS.
        AnalyzeExpenseResponse response = responseWith(typedField("INVOICE_RECEIPT_DATE", "15/08/2026 18:28:00", 80.0f, null));

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.receiptDate()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(result.receiptTime()).isEqualTo(LocalTime.of(18, 28, 0));
    }

    /**
     * Requirement 4: Indian GST receipts report CGST/SGST/IGST as separate label-matched fields,
     * not a single TAX line — the total must be their sum, never just one component.
     */
    @Test
    void parse_sumsGstComponents_whenCgstAndSgstBothPresent() {
        AnalyzeExpenseResponse response = responseWith(
                labeledField("CGST", "14.50", 80.0f),
                labeledField("SGST", "14.50", 80.0f),
                typedField("TOTAL", "609.00", 80.0f, "INR")
        );

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.taxAmount()).isEqualByComparingTo("29.00");
    }

    @Test
    void parse_sumsAllThreeGstComponents_whenCgstSgstAndIgstPresent() {
        AnalyzeExpenseResponse response = responseWith(
                labeledField("CGST", "9.00", 80.0f),
                labeledField("SGST", "9.00", 80.0f),
                labeledField("IGST", "5.00", 80.0f)
        );

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.taxAmount()).isEqualByComparingTo("23.00");
    }

    @Test
    void parse_fallsBackToPlainTaxField_whenNoGstComponentsPresent() {
        AnalyzeExpenseResponse response = responseWith(typedField("TAX", "10.00", 80.0f, null));

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.taxAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    void parse_extractsPaymentMethod_byFieldLabel() {
        AnalyzeExpenseResponse response = responseWith(labeledField("Payment Method", "UPI", 80.0f));

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.paymentMethod()).isEqualTo("UPI");
    }

    /**
     * Issue 1 exact scenario: Subtotal=798.00, CGST=19.95, SGST=19.95, and the final payable
     * amount printed as "UN-PAID" rather than under the standard TOTAL type. The old
     * implementation returned 798 (the subtotal, taken from a differently-shaped "Total" field);
     * the fix must recognize UN-PAID/UNPAID as a total-amount label and reconcile it against
     * subtotal + tax (798 + 39.90 = 837.90) rather than taking the first amount found.
     */
    @Test
    void parse_mapsFinalPayableAmount_notSubtotal_whenTotalLabeledUnpaid() {
        AnalyzeExpenseResponse response = responseWith(
                typedField("SUBTOTAL", "798.00", 90.0f, null),
                labeledField("CGST", "19.95", 90.0f),
                labeledField("SGST", "19.95", 90.0f),
                labeledField("UN-PAID", "837.90", 90.0f)
        );

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.subtotal()).isEqualByComparingTo("798.00");
        assertThat(result.taxAmount()).isEqualByComparingTo("39.90");
        assertThat(result.totalAmount()).isEqualByComparingTo("837.90");
    }

    /**
     * A "Total" field that actually just repeats the subtotal must be overridden by a
     * "Grand Total"/"Balance Due"-style label that reconciles with subtotal + tax, rather than
     * being accepted just because it has the standard TOTAL ExpenseType.
     */
    @Test
    void parse_prefersReconcilingLabel_overNonReconcilingStandardTotalField() {
        AnalyzeExpenseResponse response = responseWith(
                typedField("SUBTOTAL", "100.00", 90.0f, null),
                typedField("TAX", "10.00", 90.0f, null),
                typedField("TOTAL", "100.00", 90.0f, null), // mislabeled — actually equals subtotal
                labeledField("Balance Due", "110.00", 90.0f)
        );

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.totalAmount()).isEqualByComparingTo("110.00");
    }

    /** When nothing reconciles (no subtotal/tax available at all), the standard TOTAL type still wins as the highest-priority candidate. */
    @Test
    void parse_fallsBackToStandardTotalField_whenNoReconciliationPossible() {
        AnalyzeExpenseResponse response = responseWith(typedField("TOTAL", "50.00", 90.0f, null));

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.totalAmount()).isEqualByComparingTo("50.00");
    }

    /** Issue 2: every documented supported date format must parse to the same LocalDate. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "05 Jun 2026", "05-06-2026", "05/06/2026", "2026-06-05", "Jun 05, 2026"
    })
    void parse_parsesEveryDocumentedDateFormat(String dateText) {
        AnalyzeExpenseResponse response = responseWith(typedField("INVOICE_RECEIPT_DATE", dateText, 90.0f, null));

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.receiptDate()).isEqualTo(LocalDate.of(2026, 6, 5));
    }

    /** Issue 2: falls back to a label scan (e.g. a custom "Travel Date" field) when no standard date ExpenseType is present. */
    @Test
    void parse_fallsBackToLabelScan_whenNoStandardDateTypePresent() {
        AnalyzeExpenseResponse response = responseWith(labeledField("Travel Date", "2026-06-05", 90.0f));

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.receiptDate()).isEqualTo(LocalDate.of(2026, 6, 5));
    }

    /** Issue 3: every documented supported time format must parse to the same LocalTime. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "09:09 PM", "09:09:14 PM", "21:09:14"
    })
    void parse_parsesEveryDocumentedTimeFormat_whenSecondsPresentOrPmGiven(String timeText) {
        AnalyzeExpenseResponse response = responseWith(typedField("RECEIPT_TIME", timeText, 90.0f, null));

        ParsedReceiptData result = parser.parse(response);

        // "21:09" (no seconds, no AM/PM) is ambiguous only in that it lacks seconds — checked separately below.
        assertThat(result.receiptTime().getHour()).isEqualTo(21);
        assertThat(result.receiptTime().getMinute()).isEqualTo(9);
    }

    @Test
    void parse_parsesTimeWithoutSeconds() {
        AnalyzeExpenseResponse response = responseWith(typedField("RECEIPT_TIME", "21:09", 90.0f, null));

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.receiptTime()).isEqualTo(LocalTime.of(21, 9));
    }

    @Test
    void parse_fallsBackToLabelScan_forTime_whenNoStandardTimeTypePresent() {
        AnalyzeExpenseResponse response = responseWith(labeledField("Departure Time", "09:09:14 PM", 90.0f));

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.receiptTime()).isEqualTo(LocalTime.of(21, 9, 14));
    }

    /** Issue 4: currency symbol/keyword detection when Textract's structured currency sub-field is absent. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"₹837.90", "INR 837.90", "Rs. 837.90", "837.90 Rupees"})
    void parse_detectsCurrency_fromSymbolOrKeyword_whenNoStructuredCurrencyField(String totalText) {
        AnalyzeExpenseResponse response = responseWith(typedField("TOTAL", totalText, 90.0f, null));

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.currencyCode()).isEqualTo("INR");
    }

    @Test
    void parse_detectsUsdCurrency_fromDollarSymbol() {
        AnalyzeExpenseResponse response = responseWith(typedField("TOTAL", "$123.45", 90.0f, null));

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.currencyCode()).isEqualTo("USD");
    }

    @Test
    void parse_prefersStructuredCurrencyField_overTextScan() {
        AnalyzeExpenseResponse response = responseWith(typedField("TOTAL", "$123.45", 90.0f, "EUR"));

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.currencyCode()).isEqualTo("EUR");
    }

    /** Issue 5: merchant name falls back to a label scan when the standard VENDOR_NAME type is absent. */
    @Test
    void parse_fallsBackToLabelScan_forMerchantName_whenVendorNameTypeAbsent() {
        AnalyzeExpenseResponse response = responseWith(labeledField("Restaurant Name", "Pizza Hut", 90.0f));

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.merchantName()).isEqualTo("Pizza Hut");
    }

    // ------------------------------------------------------------------------------------------
    // Task 15 — realistic full-document scenarios across receipt/invoice types. Bus/flight/train
    // tickets are deliberately not covered here: they route through AnalyzeDocument and
    // TravelDocumentResponseParser instead of this AnalyzeExpense-based parser (see
    // TravelDocumentResponseParserTest). Multi-page PDF handling is likewise not a parser concern
    // — a multi-page PDF still produces one ExpenseDocument with the same summaryFields shape;
    // page count is handled entirely at the Textract-call layer (TextractServiceImpl).
    // ------------------------------------------------------------------------------------------

    /** The exact reported bug scenario: a legal-entity VENDOR_NAME field alongside the real brand name, ₹ with no structured currency, and a time-only RECEIPT_TIME field. */
    @Test
    void parse_restaurantReceipt_germanBakeryScenario_extractsEverythingCorrectly() {
        AnalyzeExpenseResponse response = responseWith(
                typedField("VENDOR_NAME", "UDANE SONS ENTERPRISES LLP", 88.0f, null),
                typedField("VENDOR_NAME", "German Bakery", 84.0f, null),
                typedField("INVOICE_RECEIPT_DATE", "05/06/2026", 90.0f, null),
                typedField("RECEIPT_TIME", "13:52", 90.0f, null),
                typedField("SUBTOTAL", "798.00", 90.0f, null),
                labeledField("CGST", "19.95", 90.0f),
                labeledField("SGST", "19.95", 90.0f),
                typedField("TOTAL", "798.00", 90.0f, null),
                labeledField("Grand Total", "837.90", 90.0f),
                typedField("INVOICE_RECEIPT_ID", "GB-2026-0605", 90.0f, null)
        );

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.merchantName()).isEqualTo("German Bakery");
        assertThat(result.receiptDate()).isEqualTo(LocalDate.of(2026, 6, 5));
        assertThat(result.receiptTime()).isEqualTo(LocalTime.of(13, 52));
        assertThat(result.subtotal()).isEqualByComparingTo("798.00");
        assertThat(result.taxAmount()).isEqualByComparingTo("39.90");
        assertThat(result.totalAmount()).isEqualByComparingTo("837.90");
        assertThat(result.invoiceNumber()).isEqualTo("GB-2026-0605");
    }

    @Test
    void parse_fuelReceipt_extractsVendorDateTotalAndPaymentMethod_withNoTaxLine() {
        AnalyzeExpenseResponse response = responseWith(
                typedField("VENDOR_NAME", "Indian Oil Corporation", 90.0f, null),
                typedField("DATE", "05-06-2026", 90.0f, null),
                typedField("TOTAL", "2500.00", 90.0f, "INR"),
                labeledField("Payment Method", "Credit Card", 90.0f)
        );

        ParsedReceiptData result = parser.parse(response);

        // "Corporation" is treated the same as any other legal-entity suffix (Task 12) and
        // stripped for display — "Indian Oil" is the cleaner customer-facing name either way.
        assertThat(result.merchantName()).isEqualTo("Indian Oil");
        assertThat(result.receiptDate()).isEqualTo(LocalDate.of(2026, 6, 5));
        assertThat(result.totalAmount()).isEqualByComparingTo("2500.00");
        assertThat(result.currencyCode()).isEqualTo("INR");
        assertThat(result.paymentMethod()).isEqualTo("Credit Card");
        assertThat(result.taxAmount()).isNull();
    }

    @Test
    void parse_hotelInvoice_usesServiceTaxAndGrandTotal_withVisaPayment() {
        AnalyzeExpenseResponse response = responseWith(
                typedField("VENDOR_NAME", "Taj Hotels", 90.0f, null),
                labeledField("Invoice Number", "HTL-2026-004", 90.0f),
                typedField("SUBTOTAL", "5000.00", 90.0f, null),
                labeledField("Service Tax", "600.00", 90.0f),
                labeledField("Grand Total", "5600.00", 90.0f),
                labeledField("Payment Method", "Visa", 90.0f)
        );

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.merchantName()).isEqualTo("Taj Hotels");
        assertThat(result.invoiceNumber()).isEqualTo("HTL-2026-004");
        assertThat(result.taxAmount()).isEqualByComparingTo("600.00");
        assertThat(result.totalAmount()).isEqualByComparingTo("5600.00");
        assertThat(result.paymentMethod()).isEqualTo("Visa");
    }

    @Test
    void parse_medicalReceipt_extractsBillNumberAndCashPayment() {
        AnalyzeExpenseResponse response = responseWith(
                typedField("VENDOR_NAME", "Apollo Pharmacy", 90.0f, null),
                labeledField("Bill No", "APL-88213", 90.0f),
                typedField("DATE", "2026-06-05", 90.0f, null),
                typedField("TOTAL", "455.50", 90.0f, null),
                labeledField("Payment Method", "Cash", 90.0f)
        );

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.merchantName()).isEqualTo("Apollo Pharmacy");
        assertThat(result.invoiceNumber()).isEqualTo("APL-88213");
        assertThat(result.totalAmount()).isEqualByComparingTo("455.50");
        assertThat(result.paymentMethod()).isEqualTo("Cash");
    }

    @Test
    void parse_gstInvoice_reconcilesUnpaidLabelAgainstSubtotalPlusGst_overAMisleadingTotalField() {
        AnalyzeExpenseResponse response = responseWith(
                typedField("VENDOR_NAME", "Sharma Traders", 90.0f, null),
                typedField("SUBTOTAL", "798.00", 90.0f, null),
                labeledField("CGST", "19.95", 90.0f),
                labeledField("SGST", "19.95", 90.0f),
                typedField("TOTAL", "798.00", 90.0f, null),
                labeledField("UN-PAID", "837.90", 90.0f)
        );

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.taxAmount()).isEqualByComparingTo("39.90");
        assertThat(result.totalAmount()).isEqualByComparingTo("837.90");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"AED 200.00, AED", "SAR 300.00, SAR", "'¥1000', JPY"})
    void parse_supportsMultipleCurrencies_beyondInrUsdEurGbp(String totalText, String expectedCurrency) {
        AnalyzeExpenseResponse response = responseWith(typedField("TOTAL", totalText, 90.0f, null));

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.currencyCode()).isEqualTo(expectedCurrency);
    }

    /**
     * "Blurred receipt": every field Textract did detect carries very low confidence, and several
     * expected fields (date, tax) are entirely missing. The parser must degrade gracefully — no
     * exception, every missing field left {@code null}, and the low confidence actually reflected
     * in {@code confidenceScore} rather than masked.
     */
    @Test
    void parse_blurredReceipt_degradesGracefully_withLowConfidenceAndMissingFields() {
        AnalyzeExpenseResponse response = responseWith(
                typedField("VENDOR_NAME", "Cafe Coffee Day", 22.0f, null),
                typedField("TOTAL", "150.00", 18.0f, null)
        );

        ParsedReceiptData result = parser.parse(response);

        assertThat(result.merchantName()).isEqualTo("Cafe Coffee Day");
        assertThat(result.totalAmount()).isEqualByComparingTo("150.00");
        assertThat(result.receiptDate()).isNull();
        assertThat(result.taxAmount()).isNull();
        assertThat(result.confidenceScore()).isLessThan(new BigDecimal("0.25"));
    }

    private AnalyzeExpenseResponse responseWith(ExpenseField... fields) {
        ExpenseDocument document = ExpenseDocument.builder().summaryFields(List.of(fields)).build();
        return AnalyzeExpenseResponse.builder().expenseDocuments(List.of(document)).build();
    }

    private ExpenseField typedField(String type, String text, float confidence, String currencyCode) {
        ExpenseField.Builder builder = ExpenseField.builder()
                .type(ExpenseType.builder().text(type).build())
                .valueDetection(ExpenseDetection.builder().text(text).confidence(confidence).build());
        if (currencyCode != null) {
            builder.currency(ExpenseCurrency.builder().code(currencyCode).build());
        }
        return builder.build();
    }

    /** Simulates an OTHER-type Textract field recognized only by its label (e.g. CGST/SGST/IGST/payment method — none of which have a standard ExpenseType). */
    private ExpenseField labeledField(String label, String text, float confidence) {
        return ExpenseField.builder()
                .type(ExpenseType.builder().text("OTHER").build())
                .labelDetection(ExpenseDetection.builder().text(label).confidence(confidence).build())
                .valueDetection(ExpenseDetection.builder().text(text).confidence(confidence).build())
                .build();
    }
}
