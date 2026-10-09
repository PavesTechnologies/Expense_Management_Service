package com.expense_management_service.service.impl;

import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.ExpenseField;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.expense_management_service.service.impl.ExpenseFieldSupport.findAllFieldsByLabelContaining;
import static com.expense_management_service.service.impl.ExpenseFieldSupport.normalizedConfidenceOf;
import static com.expense_management_service.service.impl.ExpenseFieldSupport.textOf;

/**
 * Task 1/12/13: selects the customer-facing merchant/business name Textract detected — never
 * just "the first VENDOR_NAME field", since a single receipt frequently carries more than one
 * name-shaped field (a brand/logo line and a separate legal registration line printed near the
 * GSTIN), and the legal line is not what an employee or approver wants to see on an expense line.
 * <p>
 * Strategy, in priority order:
 * <ol>
 *   <li>Every {@code VENDOR_NAME}-type field (all of them, not just the first).</li>
 *   <li>Label-scan fallback (MERCHANT/VENDOR/STORE NAME/RESTAURANT/BUSINESS NAME/OPERATOR) for
 *       receipts where Textract never recognized a standard VENDOR_NAME field at all.</li>
 *   <li>Task 13 raw-OCR fallback: the topmost LINE block on page 1 by reading position (not list
 *       order), skipping anything address/phone/GSTIN/FSSAI-shaped — used only when neither of
 *       the above found anything at all.</li>
 * </ol>
 * Every candidate is cleaned (GSTIN/FSSAI/phone/address lines and trailing legal suffixes
 * stripped) and classified as legal-entity-shaped or not; a non-legal-entity candidate always
 * wins over a legal-entity one, and the shortest wins within the same class — this is what
 * prefers "German Bakery" over "UDANE SONS ENTERPRISES LLP" when Textract reports both. Candidates
 * are never concatenated, and cleaning never blanks out the only candidate available.
 */
final class MerchantExtractor {

    private static final String FIELD_VENDOR_NAME = "VENDOR_NAME";

    private static final List<String> MERCHANT_LABEL_KEYWORDS = List.of(
            "MERCHANT", "VENDOR", "STORE NAME", "RESTAURANT", "BUSINESS NAME", "OPERATOR");

    /** Matched case-insensitively anywhere, for classification — a legal suffix deep in the text still marks the whole candidate as "the legal name". */
    private static final Pattern LEGAL_MARKER_PATTERN = Pattern.compile(
            "(?i)\\b(PRIVATE\\s+LIMITED|PVT\\.?\\s*LTD\\.?|LLP|LIMITED|LTD\\.?|ENTERPRISES|CORPORATION|CORP\\.?|INC\\.?)\\b");

    /** Stripped only from the trailing end of the (already noise-line-filtered) display text. */
    private static final Pattern LEGAL_SUFFIX_PATTERN = Pattern.compile(
            "(?i)[,.\\s]*\\b(PRIVATE\\s+LIMITED|PVT\\.?\\s*LTD\\.?|LLP|LIMITED|LTD\\.?|ENTERPRISES|CORPORATION|CORP\\.?|INC\\.?)\\b\\s*$");

    private static final Pattern GSTIN_PATTERN = Pattern.compile("(?i)\\bGSTIN\\b");
    private static final Pattern FSSAI_PATTERN = Pattern.compile("(?i)\\bFSSAI\\b");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?:\\+?\\d[\\d\\-\\s]{7,}\\d)");
    private static final Pattern ADDRESS_HINT_PATTERN = Pattern.compile(
            "(?i)\\b(ROAD|STREET|STR\\.|NAGAR|FLOOR|BLOCK|SECTOR|PIN\\s*CODE|ZIP\\s*CODE)\\b");

    /** Deliberately conservative — a raw OCR line is a much weaker signal than a structured Textract field. */
    private static final BigDecimal OCR_FALLBACK_CONFIDENCE = new BigDecimal("0.35");

    ExtractionResult<String> extract(ExpenseFieldIndex index) {
        List<Candidate> candidates = new ArrayList<>();

        for (ExpenseField field : index.byType(FIELD_VENDOR_NAME)) {
            addIfPresent(candidates, textOf(field), normalizedConfidenceOf(field));
        }
        if (candidates.isEmpty()) {
            for (String keyword : MERCHANT_LABEL_KEYWORDS) {
                for (ExpenseField field : findAllFieldsByLabelContaining(index.allFields(), keyword)) {
                    addIfPresent(candidates, textOf(field), normalizedConfidenceOf(field));
                }
            }
        }
        if (candidates.isEmpty()) {
            topmostUsableLine(index.blocks()).ifPresent(line -> candidates.add(new Candidate(line, OCR_FALLBACK_CONFIDENCE)));
        }

        return selectBest(candidates);
    }

    private void addIfPresent(List<Candidate> candidates, String text, BigDecimal confidence) {
        if (text != null) {
            candidates.add(new Candidate(text, confidence));
        }
    }

    /**
     * Reusable merchant selection strategy (Task 1): cleans every candidate, classifies it as
     * legal-entity-shaped or not, and picks the best — non-legal-entity candidates always beat
     * legal-entity ones, and the shortest wins within the same class. Never concatenates.
     */
    private ExtractionResult<String> selectBest(List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return ExtractionResult.empty();
        }

        List<CleanedCandidate> cleaned = candidates.stream()
                .map(candidate -> new CleanedCandidate(candidate, clean(candidate.text()), isLegalEntityShaped(candidate.text())))
                .filter(c -> !c.displayText().isBlank())
                .toList();

        if (cleaned.isEmpty()) {
            // Cleaning blanked out every candidate — fall back to the first raw one untouched
            // rather than returning nothing (Task 12: never destroy the only value available).
            Candidate first = candidates.get(0);
            return ExtractionResult.of(first.text(), first.confidence());
        }

        return cleaned.stream()
                .min(Comparator.<CleanedCandidate>comparingInt(c -> c.legalEntityShaped() ? 1 : 0)
                        .thenComparingInt(c -> c.displayText().length()))
                .map(best -> ExtractionResult.of(best.displayText(), best.candidate().confidence()))
                .orElseGet(ExtractionResult::empty);
    }

    /**
     * Splits multi-line field text (Textract often returns a VENDOR_NAME field whose text spans
     * the brand line AND the GSTIN/phone/address lines printed directly under it) and drops any
     * line that looks like noise, rather than surgically editing within a line — far more robust
     * than trying to regex out sub-tokens from a single run-on line.
     */
    private String clean(String rawText) {
        String[] lines = rawText.split("\\r?\\n");
        List<String> keptLines = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !looksLikeNoiseLine(trimmed)) {
                keptLines.add(trimmed);
            }
        }
        // If every line looked like noise, keep the original untouched rather than returning
        // blank — this is the only candidate we have until a caller decides otherwise.
        String joined = keptLines.isEmpty() ? rawText.trim() : String.join(" ", keptLines);
        return LEGAL_SUFFIX_PATTERN.matcher(joined).replaceAll("").trim();
    }

    private boolean isLegalEntityShaped(String rawText) {
        return LEGAL_MARKER_PATTERN.matcher(rawText).find();
    }

    private boolean looksLikeNoiseLine(String text) {
        return GSTIN_PATTERN.matcher(text).find()
                || FSSAI_PATTERN.matcher(text).find()
                || PHONE_PATTERN.matcher(text).find()
                || ADDRESS_HINT_PATTERN.matcher(text).find();
    }

    /** Task 13: topmost LINE block on the document's first page, by reading position — skips anything address/phone/GSTIN/FSSAI-shaped. */
    private Optional<String> topmostUsableLine(List<Block> blocks) {
        return blocks.stream()
                .filter(block -> block.blockType() == BlockType.LINE)
                .filter(block -> block.page() == null || block.page() == 1)
                .filter(block -> block.text() != null && !block.text().isBlank())
                .filter(block -> !looksLikeNoiseLine(block.text()))
                .sorted(Comparator.comparing(MerchantExtractor::topOf))
                .map(Block::text)
                .findFirst();
    }

    private static float topOf(Block block) {
        if (block.geometry() == null || block.geometry().boundingBox() == null || block.geometry().boundingBox().top() == null) {
            return Float.MAX_VALUE;
        }
        return block.geometry().boundingBox().top();
    }

    private record Candidate(String text, BigDecimal confidence) {
    }

    private record CleanedCandidate(Candidate candidate, String displayText, boolean legalEntityShaped) {
    }
}
