package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Checks a supporting document before it is stored: size, extension, declared type, and the file's actual bytes.
 * The extension and the declared Content-Type are both chosen by the sender, so only the bytes prove what the file is.
 *
 * <p>Kept separate from the receipt validation on purpose: receipts accept images with WEBP and no DOCX, and changing
 * them is out of scope for this feature.
 */
@Component
public class CashAdvanceDocumentValidator {

    static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "jpg", "jpeg", "png", "docx");

    static final Map<String, Set<String>> CONTENT_TYPES_BY_EXTENSION = Map.of(
            "pdf", Set.of("application/pdf"),
            "jpg", Set.of("image/jpeg"),
            "jpeg", Set.of("image/jpeg"),
            "png", Set.of("image/png"),
            "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));

    private static final int MAX_ZIP_ENTRIES_SCANNED = 2000;

    /**
     * Validates the file. Returns the lower-case extension. Throws a {@link BusinessRuleViolationException} with a
     * message the employee can act on.
     */
    public String validate(String originalFileName, String declaredContentType, byte[] bytes, long maxBytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessRuleViolationException("The file is empty. Choose a file with content.");
        }
        if (bytes.length > maxBytes) {
            throw new BusinessRuleViolationException(
                    "The file is larger than " + (maxBytes / (1024 * 1024)) + " MB. Choose a smaller file.");
        }
        String extension = extensionOf(originalFileName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessRuleViolationException(
                    "This file type is not allowed. Upload a PDF, JPG, JPEG, PNG or DOCX file.");
        }
        String contentType = normalizeContentType(declaredContentType);
        if (!CONTENT_TYPES_BY_EXTENSION.get(extension).contains(contentType)) {
            throw new BusinessRuleViolationException(
                    "The file type does not match its extension. Check the file and try again.");
        }
        if (!matchesSignature(extension, bytes)) {
            throw new BusinessRuleViolationException(
                    "The file contents do not match its type. Check the file and try again.");
        }
        return extension;
    }

    static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.trim();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String normalizeContentType(String declared) {
        if (declared == null) {
            return "";
        }
        int semicolon = declared.indexOf(';');
        String base = semicolon < 0 ? declared : declared.substring(0, semicolon);
        return base.trim().toLowerCase(Locale.ROOT);
    }

    /** Checks the first bytes (and, for DOCX, the archive entries) against what the extension claims. */
    boolean matchesSignature(String extension, byte[] bytes) {
        return switch (extension) {
            case "pdf" -> startsWith(bytes, 0x25, 0x50, 0x44, 0x46);                                  // %PDF
            case "png" -> startsWith(bytes, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "jpg", "jpeg" -> startsWith(bytes, 0xFF, 0xD8, 0xFF);
            case "docx" -> startsWith(bytes, 0x50, 0x4B, 0x03, 0x04) && isWordDocument(bytes);       // ZIP with a Word body
            default -> false;
        };
    }

    private static boolean startsWith(byte[] bytes, int... expected) {
        if (bytes.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    /** A DOCX is a ZIP that contains the Word body and the content-types part. */
    static boolean isWordDocument(byte[] bytes) {
        boolean hasBody = false;
        boolean hasContentTypes = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            int scanned = 0;
            while ((entry = zip.getNextEntry()) != null && scanned++ < MAX_ZIP_ENTRIES_SCANNED) {
                if ("word/document.xml".equals(entry.getName())) {
                    hasBody = true;
                } else if ("[Content_Types].xml".equals(entry.getName())) {
                    hasContentTypes = true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return hasBody && hasContentTypes;
    }
}
