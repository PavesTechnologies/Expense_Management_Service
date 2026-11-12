package com.expense_management_service.service.impl;

import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.ExpenseField;

import java.math.BigDecimal;

import static com.expense_management_service.service.impl.ExpenseFieldSupport.textOf;

/**
 * Task 4: detects the receipt's currency. Textract's structured {@code currency()} sub-field
 * (only ever populated on some receipts, attached to the TOTAL field) is authoritative when
 * present; otherwise every field's own text is scanned for a currency symbol or keyword (₹/INR,
 * $/USD, €/EUR, £/GBP, AED/Dirham, SAR/Riyal, ¥/JPY/Yen — see
 * {@link ReceiptFieldParsingUtils#CURRENCY_MATCHERS}), and finally (Task 13) the raw OCR lines.
 * A bare "₹" with nothing else present still resolves to "INR" — the symbol alone is enough.
 */
final class CurrencyExtractor {

    private static final String FIELD_TOTAL = "TOTAL";

    private static final BigDecimal STRUCTURED_FIELD_CONFIDENCE = new BigDecimal("0.95");
    private static final BigDecimal FIELD_TEXT_MATCH_CONFIDENCE = new BigDecimal("0.70");
    private static final BigDecimal RAW_BLOCK_MATCH_CONFIDENCE = new BigDecimal("0.50");

    ExtractionResult<String> extract(ExpenseFieldIndex index) {
        for (ExpenseField field : index.byType(FIELD_TOTAL)) {
            String structured = field.currency() != null ? field.currency().code() : null;
            if (structured != null && !structured.isBlank()) {
                return ExtractionResult.of(structured, STRUCTURED_FIELD_CONFIDENCE);
            }
        }

        for (ExpenseField field : index.allFields()) {
            String detected = ReceiptFieldParsingUtils.detectCurrencyFromText(textOf(field));
            if (detected != null) {
                return ExtractionResult.of(detected, FIELD_TEXT_MATCH_CONFIDENCE);
            }
        }

        for (Block block : index.blocks()) {
            if (block.blockType() != BlockType.LINE) {
                continue;
            }
            String detected = ReceiptFieldParsingUtils.detectCurrencyFromText(block.text());
            if (detected != null) {
                return ExtractionResult.of(detected, RAW_BLOCK_MATCH_CONFIDENCE);
            }
        }

        return ExtractionResult.empty();
    }
}
