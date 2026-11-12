package com.expense_management_service.service.impl;

import org.junit.jupiter.api.Test;

import static com.expense_management_service.service.impl.TestExpenseFields.indexOf;
import static com.expense_management_service.service.impl.TestExpenseFields.labeledField;
import static com.expense_management_service.service.impl.TestExpenseFields.typedField;
import static org.assertj.core.api.Assertions.assertThat;

class InvoiceNumberExtractorTest {

    private final InvoiceNumberExtractor extractor = new InvoiceNumberExtractor();

    @Test
    void extract_prefersStandardInvoiceReceiptIdType_overAnyLabelMatch() {
        ExpenseFieldIndex index = indexOf(
                typedField("INVOICE_RECEIPT_ID", "INV-001", 90.0f, null),
                labeledField("Reference Number", "REF-999", 90.0f));

        assertThat(extractor.extract(index).value()).isEqualTo("INV-001");
    }

    @Test
    void extract_prefersInvoiceNumberLabel_overBillNumberAndReferenceNumber() {
        ExpenseFieldIndex index = indexOf(
                labeledField("Reference Number", "REF-999", 90.0f),
                labeledField("Bill No", "BILL-42", 90.0f),
                labeledField("Invoice Number", "INV-777", 90.0f));

        assertThat(extractor.extract(index).value()).isEqualTo("INV-777");
    }

    /** A naive "label contains INVOICE" check would wrongly treat this as a plain-Invoice match instead of the lower-priority Tax Invoice tier. */
    @Test
    void extract_doesNotConfuseTaxInvoiceLabel_withThePlainInvoiceTier() {
        ExpenseFieldIndex index = indexOf(
                labeledField("Bill No", "BILL-42", 90.0f),
                labeledField("Tax Invoice Number", "TI-123", 90.0f));

        assertThat(extractor.extract(index).value()).isEqualTo("BILL-42");
    }

    @Test
    void extract_prefersBillNumber_overTaxInvoiceAndReceiptNo() {
        ExpenseFieldIndex index = indexOf(
                labeledField("Receipt No", "RCPT-5", 90.0f),
                labeledField("Tax Invoice Number", "TI-123", 90.0f),
                labeledField("Bill No", "BILL-42", 90.0f));

        assertThat(extractor.extract(index).value()).isEqualTo("BILL-42");
    }

    @Test
    void extract_prefersTicketNumber_overReferenceNumber() {
        ExpenseFieldIndex index = indexOf(
                labeledField("Reference Number", "REF-999", 90.0f),
                labeledField("Ticket Number", "TCK-8", 90.0f));

        assertThat(extractor.extract(index).value()).isEqualTo("TCK-8");
    }

    @Test
    void extract_fallsBackToReferenceNumber_whenNothingElsePresent() {
        ExpenseFieldIndex index = indexOf(labeledField("Reference Number", "REF-999", 90.0f));

        assertThat(extractor.extract(index).value()).isEqualTo("REF-999");
    }

    @Test
    void extract_returnsEmpty_whenNoInvoiceNumberShapedFieldExists() {
        ExpenseFieldIndex index = indexOf(typedField("VENDOR_NAME", "Acme", 90.0f, null));

        assertThat(extractor.extract(index).isPresent()).isFalse();
    }
}
