package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.dto.response.PaymentProofFields;
import com.expense_management_service.service.TextractService;
import com.expense_management_service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a payment screenshot (UPI or bank transfer confirmation) and returns the fields Finance records, so the
 * form can be filled from the image. Best effort: anything not found is left empty for Finance to enter.
 * The uploaded image is stored only for the read and deleted afterwards.
 */
@Service
@RequiredArgsConstructor
public class PaymentProofExtractor {

    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private static final Pattern REFERENCE_LABELLED = Pattern.compile(
            "(?i)(?:upi\\s*)?(?:transaction|txn|trans\\.?|utr|reference|ref)(?:\\s*(?:id|no\\.?|number|#))?\\s*[:#-]?\\s*([A-Za-z0-9]{8,})");
    private static final Pattern TWELVE_DIGITS = Pattern.compile("\\b(\\d{12})\\b");
    private static final Pattern PAYEE_UPI = Pattern.compile("([A-Za-z0-9._-]{2,}@[A-Za-z]{2,})");
    private static final Pattern AMOUNT = Pattern.compile("(?i)(?:₹|rs\\.?|inr)\\s*([0-9][0-9,]*(?:\\.\\d{1,2})?)");
    private static final Pattern DATE_NUMERIC = Pattern.compile("\\b(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})\\b");
    private static final Pattern DATE_WORDS = Pattern.compile("\\b(\\d{1,2})\\s+([A-Za-z]{3,9})\\s+(\\d{4})\\b");

    private final StorageService storageService;
    private final TextractService textractService;

    public PaymentProofFields extract(MultipartFile file) {
        String extension = extensionOf(file);
        String key = "payment-proof-scans/" + UUID.randomUUID() + "." + extension;
        storageService.upload(key, file);
        try {
            List<String> lines = readLines(textractService.detectDocumentText(key).blocks());
            return parse(lines);
        } finally {
            storageService.delete(key);
        }
    }

    /** Pulls the text lines out of a Textract response, in the order it returns them. */
    static List<String> readLines(List<Block> blocks) {
        List<String> lines = new ArrayList<>();
        for (Block block : blocks) {
            if (block.blockType() == BlockType.LINE && block.text() != null && !block.text().isBlank()) {
                lines.add(block.text().trim());
            }
        }
        return lines;
    }

    /** Parses the fields from the screenshot's text lines. Pure, so it can be tested without AWS. */
    public static PaymentProofFields parse(List<String> lines) {
        String text = String.join("\n", lines);
        return new PaymentProofFields(
                findReference(text).orElse(null),
                findDate(text).orElse(null),
                findPayee(text).orElse(null),
                findAmount(text).orElse(null));
    }

    static Optional<String> findReference(String text) {
        Matcher labelled = REFERENCE_LABELLED.matcher(text);
        while (labelled.find()) {
            String candidate = labelled.group(1);
            if (candidate.chars().anyMatch(Character::isDigit)) {
                return Optional.of(candidate);
            }
        }
        Matcher digits = TWELVE_DIGITS.matcher(text);
        return digits.find() ? Optional.of(digits.group(1)) : Optional.empty();
    }

    static Optional<LocalDate> findDate(String text) {
        Matcher numeric = DATE_NUMERIC.matcher(text);
        if (numeric.find()) {
            try {
                return Optional.of(LocalDate.of(Integer.parseInt(numeric.group(3)),
                        Integer.parseInt(numeric.group(2)), Integer.parseInt(numeric.group(1))));
            } catch (java.time.DateTimeException e) {
                // not a real date; try the next form
            }
        }
        Matcher words = DATE_WORDS.matcher(text);
        while (words.find()) {
            for (String pattern : new String[] {"d MMM yyyy", "d MMMM yyyy"}) {
                try {
                    return Optional.of(LocalDate.parse(
                            words.group(1) + " " + words.group(2) + " " + words.group(3),
                            DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)));
                } catch (DateTimeParseException e) {
                    // try the next pattern
                }
            }
        }
        return Optional.empty();
    }

    static Optional<String> findPayee(String text) {
        Matcher payee = PAYEE_UPI.matcher(text);
        return payee.find() ? Optional.of(payee.group(1)) : Optional.empty();
    }

    static Optional<BigDecimal> findAmount(String text) {
        Matcher amount = AMOUNT.matcher(text);
        if (!amount.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(amount.group(1).replace(",", "")));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String extensionOf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleViolationException("Upload the payment screenshot.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessRuleViolationException("The screenshot must be 10 MB or smaller.");
        }
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "application/pdf" -> "pdf";
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            default -> throw new BusinessRuleViolationException("Upload a PDF, JPG or PNG screenshot.");
        };
    }
}
