package com.expense_management_service.service.impl;

import software.amazon.awssdk.services.textract.model.ExpenseField;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

import static com.expense_management_service.service.impl.ExpenseFieldSupport.findAllFieldsByLabelContaining;
import static com.expense_management_service.service.impl.ExpenseFieldSupport.textOf;

/**
 * Task 9: extracts the payment method against a fixed, known vocabulary (Cash, Credit Card,
 * Debit Card, UPI, Google Pay, PhonePe, Paytm, Visa, MasterCard, Amex) rather than returning
 * whatever raw text happens to sit under a "Payment Method"-labeled field — a field might read
 * "Paid via UPI txn #12345", and only "UPI" is a payment method, not the whole sentence. Brand
 * names are checked before generic terms so "UPI (Google Pay)" resolves to "Google Pay", the more
 * specific and useful value. Returns {@code null} when nothing in the vocabulary is found
 * anywhere — never a guess, and never unrecognized raw text.
 */
final class PaymentMethodExtractor {

    private static final String PAYMENT_LABEL_KEYWORD = "PAYMENT";

    private record MethodMatcher(Pattern pattern, String canonicalName) {
    }

    /** Order matters: brand-specific names are checked before the generic terms they'd otherwise be swallowed by. */
    private static final List<MethodMatcher> METHOD_MATCHERS = List.of(
            new MethodMatcher(Pattern.compile("(?i)\\bGOOGLE\\s*PAY\\b|\\bGPAY\\b"), "Google Pay"),
            new MethodMatcher(Pattern.compile("(?i)\\bPHONE\\s*PE\\b"), "PhonePe"),
            new MethodMatcher(Pattern.compile("(?i)\\bPAYTM\\b"), "Paytm"),
            new MethodMatcher(Pattern.compile("(?i)\\bAMEX\\b|\\bAMERICAN\\s+EXPRESS\\b"), "Amex"),
            new MethodMatcher(Pattern.compile("(?i)\\bMASTER\\s*CARD\\b"), "MasterCard"),
            new MethodMatcher(Pattern.compile("(?i)\\bVISA\\b"), "Visa"),
            new MethodMatcher(Pattern.compile("(?i)\\bUPI\\b"), "UPI"),
            new MethodMatcher(Pattern.compile("(?i)\\bCREDIT\\s*CARD\\b"), "Credit Card"),
            new MethodMatcher(Pattern.compile("(?i)\\bDEBIT\\s*CARD\\b"), "Debit Card"),
            new MethodMatcher(Pattern.compile("(?i)\\bCASH\\b"), "Cash")
    );

    private static final BigDecimal LABELED_FIELD_CONFIDENCE = new BigDecimal("0.85");
    private static final BigDecimal GENERAL_SCAN_CONFIDENCE = new BigDecimal("0.65");

    ExtractionResult<String> extract(ExpenseFieldIndex index) {
        for (ExpenseField field : findAllFieldsByLabelContaining(index.allFields(), PAYMENT_LABEL_KEYWORD)) {
            String matched = matchVocabulary(textOf(field));
            if (matched != null) {
                return ExtractionResult.of(matched, LABELED_FIELD_CONFIDENCE);
            }
        }

        for (ExpenseField field : index.allFields()) {
            String matched = matchVocabulary(textOf(field));
            if (matched != null) {
                return ExtractionResult.of(matched, GENERAL_SCAN_CONFIDENCE);
            }
        }

        return ExtractionResult.empty();
    }

    private String matchVocabulary(String text) {
        if (text == null) {
            return null;
        }
        for (MethodMatcher matcher : METHOD_MATCHERS) {
            if (matcher.pattern().matcher(text).find()) {
                return matcher.canonicalName();
            }
        }
        return null;
    }
}
