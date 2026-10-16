package com.expense_management_service.service.impl;

import com.expense_management_service.dto.request.CashAdvanceDisbursement;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class CashAdvanceDisbursementRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    private static final MockMultipartFile PDF = new MockMultipartFile("f", "ack.pdf", "application/pdf", new byte[]{1});
    private static final MockMultipartFile PNG = new MockMultipartFile("f", "shot.png", "image/png", new byte[]{1});

    private static CashAdvanceDisbursement cash(String name, MockMultipartFile ack) {
        return new CashAdvanceDisbursement("CASH", name, ack, null, null, null, null, null, null, null);
    }

    private static CashAdvanceDisbursement bank(String reference, LocalDate date, String details) {
        return new CashAdvanceDisbursement("BANK_TRANSFER", null, null, reference, date, details, null, null, null, null);
    }

    private static CashAdvanceDisbursement upi(String txn, String payee, LocalDate date, MockMultipartFile proof) {
        return new CashAdvanceDisbursement("UPI", null, null, null, date, null, txn, payee, proof, null);
    }

    private static String message(CashAdvanceDisbursement d) {
        return assertThrows(IllegalArgumentException.class, () -> CashAdvanceDisbursementRules.validate(d, TODAY)).getMessage();
    }

    @Test
    void cash_acceptsReceiverAndPdfAcknowledgment() {
        assertDoesNotThrow(() -> CashAdvanceDisbursementRules.validate(cash("John Doe", PDF), TODAY));
    }

    @Test
    void cash_requiresReceiverName() {
        assertEquals("Enter the name of the person who received the cash.", message(cash("   ", PDF)));
    }

    @Test
    void cash_requiresSignedAcknowledgment() {
        assertEquals("Upload the signed acknowledgment.", message(cash("John Doe", null)));
    }

    @Test
    void cash_rejectsUnsupportedFileType() {
        MockMultipartFile docx = new MockMultipartFile("f", "ack.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", new byte[]{1});
        assertEquals("Upload a PDF, JPG or PNG file.", message(cash("John Doe", docx)));
    }

    @Test
    void cash_rejectsFileOver10Mb() {
        MockMultipartFile big = new MockMultipartFile("f", "ack.pdf", "application/pdf",
                new byte[(int) CashAdvanceDisbursementRules.MAX_PROOF_BYTES + 1]);
        assertEquals("The file must be 10 MB or smaller.", message(cash("John Doe", big)));
    }

    @Test
    void bankTransfer_acceptsCompleteDetails() {
        assertDoesNotThrow(() -> CashAdvanceDisbursementRules.validate(bank("UTR1", TODAY, "HDFC 1234"), TODAY));
    }

    @Test
    void bankTransfer_requiresReference_date_andDetails() {
        assertEquals("Enter the transaction reference.", message(bank(" ", TODAY, "HDFC")));
        assertEquals("Enter the transfer date.", message(bank("UTR1", null, "HDFC")));
        assertEquals("Enter the transfer details (bank and beneficiary account).", message(bank("UTR1", TODAY, "")));
    }

    @Test
    void upi_acceptsCompleteDetails() {
        assertDoesNotThrow(() -> CashAdvanceDisbursementRules.validate(upi("T1", "asha@hdfcbank", TODAY, PNG), TODAY));
    }

    @Test
    void upi_requiresTransactionIdValidPayeeDateAndScreenshot() {
        assertEquals("Enter the UPI transaction ID.", message(upi(" ", "asha@hdfcbank", TODAY, PNG)));
        assertEquals("Enter a valid payee UPI ID (for example name@bank).", message(upi("T1", "not-a-upi", TODAY, PNG)));
        assertEquals("Enter the payment date.", message(upi("T1", "asha@hdfcbank", null, PNG)));
        assertEquals("Upload the payment screenshot.", message(upi("T1", "asha@hdfcbank", TODAY, null)));
    }

    @Test
    void transferDate_cannotBeInFuture() {
        assertEquals("Transfer date cannot be in the future.",
                message(bank("UTR1", TODAY.plusDays(1), "HDFC")));
    }

    @Test
    void mode_isRequiredAndMustBeKnown() {
        assertEquals("Select how the payment was made.",
                message(new CashAdvanceDisbursement(null, null, null, null, null, null, null, null, null, null)));
        assertEquals("Unknown disbursement mode: CHEQUE", message(
                new CashAdvanceDisbursement("CHEQUE", null, null, null, null, null, null, null, null, null)));
    }

    @Test
    void mode_isCaseInsensitive() {
        assertEquals("CASH", CashAdvanceDisbursementRules.normalizedMode(" cash "));
    }
}
