package com.expense_management_service.service.impl;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Free-text parsing shared by every Textract response parser ({@code TextractResponseParserImpl}
 * for {@code AnalyzeExpense}, the travel-document parser for {@code AnalyzeDocument}) — dates,
 * times, amounts, and currencies are extracted from Textract's raw detected text the same way
 * regardless of which Textract API produced it, so this logic lives in exactly one place.
 */
@Slf4j
final class ReceiptFieldParsingUtils {

    private ReceiptFieldParsingUtils() {
    }

    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2}:\\d{2}(?::\\d{2})?\\s?(?:[AaPp][Mm])?)");

    /**
     * Common receipt date formats, tried in order — Textract returns the date as free text,
     * format varies by vendor. Day-first (dd/MM/yyyy) is tried before month-first (M/d/yyyy) for
     * slash-separated dates: this system's receipts are predominantly Indian (GST, INR, UPI —
     * see every example throughout this parser), where day-first is the norm, and an ambiguous
     * date like "05/06/2026" must resolve the same way this system's own documented examples do
     * (5 June, not May 6). This is safe for genuine month-first input too — a day value over 12
     * (e.g. "12/25/2026") simply fails day-first parsing and falls through to month-first below.
     */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            // 2-digit-year variants (e.g. "05/06/26") — Java's default reduced-year base for a
            // 2-digit "yy" pattern maps 00-99 to 2000-2099, which is what every receipt this
            // system processes falls within.
            DateTimeFormatter.ofPattern("dd/MM/yy"),
            DateTimeFormatter.ofPattern("d/M/yy"),
            DateTimeFormatter.ofPattern("M/d/yy"),
            DateTimeFormatter.ofPattern("MM/dd/yy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d.MMM.yyyy", Locale.ENGLISH)
    );

    private static final List<DateTimeFormatter> TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("H:mm:ss"),
            DateTimeFormatter.ofPattern("H:mm"),
            DateTimeFormatter.ofPattern("h:mm:ss a", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    );

    /** Checked only when Textract's own structured currency sub-field (not available on every API) is absent. */
    static final List<CurrencyMatcher> CURRENCY_MATCHERS = List.of(
            new CurrencyMatcher(Pattern.compile("₹"), "INR"),
            new CurrencyMatcher(Pattern.compile("(?i)\\bINR\\b"), "INR"),
            new CurrencyMatcher(Pattern.compile("(?i)\\bRS\\.?\\b"), "INR"),
            new CurrencyMatcher(Pattern.compile("(?i)\\bRUPEES?\\b"), "INR"),
            new CurrencyMatcher(Pattern.compile("\\$"), "USD"),
            new CurrencyMatcher(Pattern.compile("(?i)\\bUSD\\b"), "USD"),
            new CurrencyMatcher(Pattern.compile("(?i)\\bDOLLARS?\\b"), "USD"),
            new CurrencyMatcher(Pattern.compile("€"), "EUR"),
            new CurrencyMatcher(Pattern.compile("(?i)\\bEUR\\b"), "EUR"),
            new CurrencyMatcher(Pattern.compile("£"), "GBP"),
            new CurrencyMatcher(Pattern.compile("(?i)\\bGBP\\b"), "GBP"),
            new CurrencyMatcher(Pattern.compile("(?i)\\bPOUNDS?\\b"), "GBP"),
            new CurrencyMatcher(Pattern.compile("(?i)\\bAED\\b"), "AED"),
            new CurrencyMatcher(Pattern.compile("(?i)\\bDIRHAMS?\\b"), "AED"),
            new CurrencyMatcher(Pattern.compile("(?i)\\bSAR\\b"), "SAR"),
            new CurrencyMatcher(Pattern.compile("(?i)\\bRIYALS?\\b"), "SAR"),
            new CurrencyMatcher(Pattern.compile("¥"), "JPY"),
            new CurrencyMatcher(Pattern.compile("(?i)\\bJPY\\b"), "JPY"),
            new CurrencyMatcher(Pattern.compile("(?i)\\bYEN\\b"), "JPY")
    );

    record CurrencyMatcher(Pattern pattern, String code) {
    }

    static LocalDate parseDate(String rawText) {
        if (rawText == null) {
            return null;
        }
        String datePart = stripTime(rawText);
        for (DateTimeFormatter formatter : DATE_FORMATS) {
            try {
                return LocalDate.parse(datePart, formatter);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        log.warn("Could not parse Textract date '{}' with any known format — leaving it unset", rawText);
        return null;
    }

    static LocalTime parseTime(String rawText) {
        if (rawText == null) {
            return null;
        }
        String normalized = rawText.trim().toUpperCase(Locale.ROOT);
        for (DateTimeFormatter formatter : TIME_FORMATS) {
            try {
                return LocalTime.parse(normalized, formatter);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        log.warn("Could not parse Textract time '{}' with any known format — leaving it unset", rawText);
        return null;
    }

    static String extractEmbeddedTime(String rawText) {
        if (rawText == null) {
            return null;
        }
        Matcher matcher = TIME_PATTERN.matcher(rawText);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /** Strips a matched time substring (and any leftover separators) so only the date part remains for date parsing. */
    private static String stripTime(String rawText) {
        return TIME_PATTERN.matcher(rawText).replaceAll("").replaceAll("[,\\s]+$", "").trim();
    }

    static BigDecimal parseAmount(String rawText, String context) {
        if (rawText == null) {
            return null;
        }
        String numeric = rawText.replaceAll("[^0-9.-]", "");
        if (numeric.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(numeric).setScale(4, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            log.warn("Could not parse Textract amount '{}' for field {} — leaving it unset", rawText, context);
            return null;
        }
    }

    /** Scans free text for a currency symbol or keyword (₹/INR/Rs./Rupees, $/USD/Dollars, €/EUR, £/GBP/Pounds). */
    static String detectCurrencyFromText(String text) {
        if (text == null) {
            return null;
        }
        for (CurrencyMatcher matcher : CURRENCY_MATCHERS) {
            if (matcher.pattern().matcher(text).find()) {
                return matcher.code();
            }
        }
        return null;
    }
}
