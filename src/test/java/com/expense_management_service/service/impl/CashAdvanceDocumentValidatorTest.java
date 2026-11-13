package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** File limits, formats and real content, for supporting documents on cash advance requests. */
class CashAdvanceDocumentValidatorTest {

    private static final long FIVE_MB = 5L * 1024 * 1024;
    private static final String DOCX_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final CashAdvanceDocumentValidator validator = new CashAdvanceDocumentValidator();

    private static byte[] pdf() {
        return "%PDF-1.4\n%test content\n".getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] png() {
        return new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    }

    private static byte[] jpeg() {
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
    }

    private static byte[] docx(boolean withWordBody) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
                zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                if (withWordBody) {
                    zip.putNextEntry(new ZipEntry("word/document.xml"));
                    zip.write("<w:document/>".getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                } else {
                    zip.putNextEntry(new ZipEntry("readme.txt"));
                    zip.write("not a word file".getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ accepted formats

    @Test
    void accepts_eachAllowedFormat_withItsRealContent() {
        assertEquals("pdf", validator.validate("itinerary.pdf", "application/pdf", pdf(), FIVE_MB));
        assertEquals("png", validator.validate("client.png", "image/png", png(), FIVE_MB));
        assertEquals("jpg", validator.validate("photo.jpg", "image/jpeg", jpeg(), FIVE_MB));
        assertEquals("jpeg", validator.validate("photo.jpeg", "image/jpeg", jpeg(), FIVE_MB));
        assertEquals("docx", validator.validate("estimate.docx", DOCX_TYPE, docx(true), FIVE_MB));
    }

    @Test
    void acceptsAnUpperCaseExtension_andAContentTypeWithParameters() {
        assertEquals("pdf", validator.validate("ITINERARY.PDF", "application/pdf; charset=binary", pdf(), FIVE_MB));
    }

    // ------------------------------------------------------------------ size limits

    @Test
    void accepts_aFileExactlyAtTheLimit() {
        byte[] atLimit = new byte[(int) FIVE_MB];
        System.arraycopy(pdf(), 0, atLimit, 0, pdf().length);
        assertDoesNotThrow(() -> validator.validate("big.pdf", "application/pdf", atLimit, FIVE_MB));
    }

    @Test
    void refuses_aFileOneByteOverTheLimit() {
        byte[] overLimit = new byte[(int) FIVE_MB + 1];
        System.arraycopy(pdf(), 0, overLimit, 0, pdf().length);
        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class,
                () -> validator.validate("big.pdf", "application/pdf", overLimit, FIVE_MB));
        assertTrue(ex.getMessage().contains("larger than 5 MB"), ex.getMessage());
    }

    @Test
    void refuses_anEmptyFile() {
        assertThrows(BusinessRuleViolationException.class,
                () -> validator.validate("empty.pdf", "application/pdf", new byte[0], FIVE_MB));
        assertThrows(BusinessRuleViolationException.class,
                () -> validator.validate("empty.pdf", "application/pdf", null, FIVE_MB));
    }

    // ------------------------------------------------------------------ extensions and declared types

    @ParameterizedTest
    @CsvSource({"malware.exe", "sheet.xlsx", "old.doc", "photo.gif", "image.webp", "noextension"})
    void refuses_aFormatThatIsNotAllowed(String fileName) {
        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class,
                () -> validator.validate(fileName, "application/pdf", pdf(), FIVE_MB));
        assertTrue(ex.getMessage().contains("not allowed"), ex.getMessage());
    }

    @Test
    void refuses_aDeclaredTypeThatDoesNotMatchTheExtension() {
        assertThrows(BusinessRuleViolationException.class,
                () -> validator.validate("itinerary.pdf", "image/png", pdf(), FIVE_MB));
    }

    // ------------------------------------------------------------------ actual content

    @Test
    void refuses_anExecutableRenamedToPdf_becauseItsBytesAreNotAPdf() {
        byte[] exe = {0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00};   // "MZ" executable header
        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class,
                () -> validator.validate("invoice.pdf", "application/pdf", exe, FIVE_MB));
        assertTrue(ex.getMessage().contains("do not match"), ex.getMessage());
    }

    @Test
    void refuses_aPngWithAJpegBody() {
        assertThrows(BusinessRuleViolationException.class,
                () -> validator.validate("photo.png", "image/png", jpeg(), FIVE_MB));
    }

    @Test
    void refuses_aDocxThatIsAZipWithoutAWordBody() {
        assertThrows(BusinessRuleViolationException.class,
                () -> validator.validate("notes.docx", DOCX_TYPE, docx(false), FIVE_MB));
    }

    @Test
    void refuses_aDocxThatIsNotAZipAtAll() {
        assertThrows(BusinessRuleViolationException.class,
                () -> validator.validate("estimate.docx", DOCX_TYPE, pdf(), FIVE_MB));
    }
}
