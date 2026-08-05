package com.expense_management_service.service.impl;

import com.expense_management_service.dto.ocr.ParsedReceiptData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.textract.model.AnalyzeDocumentResponse;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.EntityType;
import software.amazon.awssdk.services.textract.model.RelationshipType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Maps AWS Textract's {@code AnalyzeDocument} (forms + tables) response into a
 * {@link ParsedReceiptData} — used for structured documents that are not invoices/receipts
 * (bus/flight/train tickets, boarding passes), where {@code AnalyzeExpense}'s expense-specific
 * field types don't apply but the document still has labeled key/value pairs (e.g. "PNR",
 * "Departure Date", "Fare").
 * <p>
 * {@code AnalyzeDocument} returns a flat graph of {@link Block}s (KEY_VALUE_SET, WORD, LINE, ...)
 * linked by {@code relationships()} rather than Textract's expense-specific {@code ExpenseField}
 * structure — {@link #extractFormFields} walks that graph once into a plain label→text map, then
 * every field below is matched against that map by label keyword, the same "label, not position"
 * principle {@code TextractResponseParserImpl} follows for receipts. Date/time/amount/currency
 * text parsing is shared with it via {@link ReceiptFieldParsingUtils}.
 */
@Component
@Slf4j
public class TravelDocumentResponseParser {

    private static final List<String> OPERATOR_LABEL_KEYWORDS = List.of(
            "AIRLINE", "OPERATOR", "CARRIER", "RAILWAY", "BUS COMPANY", "TRAVELS", "FLIGHT NUMBER");
    private static final List<String> TICKET_NUMBER_LABEL_KEYWORDS = List.of(
            "PNR", "TICKET NO", "TICKET NUMBER", "BOOKING ID", "BOOKING REFERENCE", "CONFIRMATION NUMBER", "TICKET");
    private static final List<String> AMOUNT_LABEL_KEYWORDS = List.of(
            "TOTAL FARE", "FARE", "TOTAL AMOUNT", "AMOUNT", "PRICE", "TOTAL");
    private static final String PAYMENT_METHOD_LABEL_KEYWORD = "PAYMENT";
    private static final String DATE_LABEL_KEYWORD = "DATE";
    private static final String TIME_LABEL_KEYWORD = "TIME";

    public ParsedReceiptData parse(AnalyzeDocumentResponse rawResponse) {
        Map<String, String> fields = extractFormFields(rawResponse);
        if (fields.isEmpty()) {
            log.debug("AnalyzeDocument found no form fields to parse");
            return new ParsedReceiptData(null, null, null, null, null, null, null, null, null, BigDecimal.ZERO);
        }

        String operatorName = firstValueByLabelKeywords(fields, OPERATOR_LABEL_KEYWORDS);
        String ticketNumber = firstValueByLabelKeywords(fields, TICKET_NUMBER_LABEL_KEYWORDS);
        String dateText = firstValueByLabelKeywords(fields, List.of(DATE_LABEL_KEYWORD));
        String timeText = firstValueByLabelKeywords(fields, List.of(TIME_LABEL_KEYWORD));
        String amountText = firstValueByLabelKeywords(fields, AMOUNT_LABEL_KEYWORDS);
        String paymentMethod = firstValueByLabelKeywords(fields, List.of(PAYMENT_METHOD_LABEL_KEYWORD));

        LocalDate travelDate = ReceiptFieldParsingUtils.parseDate(dateText);
        LocalTime travelTime = ReceiptFieldParsingUtils.parseTime(
                timeText != null ? timeText : ReceiptFieldParsingUtils.extractEmbeddedTime(dateText));
        BigDecimal fareAmount = ReceiptFieldParsingUtils.parseAmount(amountText, "FARE");
        String currencyCode = fields.values().stream()
                .map(ReceiptFieldParsingUtils::detectCurrencyFromText)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        return new ParsedReceiptData(operatorName, ticketNumber, travelDate, travelTime, currencyCode,
                null, null, fareAmount, paymentMethod, BigDecimal.ZERO);
    }

    private String firstValueByLabelKeywords(Map<String, String> fields, List<String> keywords) {
        for (String keyword : keywords) {
            String normalizedKeyword = keyword.toUpperCase(Locale.ROOT);
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                if (entry.getKey().toUpperCase(Locale.ROOT).contains(normalizedKeyword)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    /**
     * Flattens Textract's KEY_VALUE_SET block graph into a plain label→text map. A KEY block's
     * own text comes from its CHILD WORD blocks; its paired VALUE block (found via a VALUE-type
     * relationship) is itself another KEY_VALUE_SET block whose text likewise comes from its
     * CHILD WORD blocks.
     */
    Map<String, String> extractFormFields(AnalyzeDocumentResponse response) {
        if (response == null || response.blocks() == null) {
            return Map.of();
        }
        List<Block> blocks = response.blocks();
        Map<String, Block> blocksById = blocks.stream()
                .collect(Collectors.toMap(Block::id, block -> block, (first, second) -> first));

        Map<String, String> fields = new LinkedHashMap<>();
        for (Block block : blocks) {
            if (block.blockType() != BlockType.KEY_VALUE_SET || block.entityTypes() == null
                    || !block.entityTypes().contains(EntityType.KEY)) {
                continue;
            }
            String keyText = collectChildWords(block, blocksById);
            if (keyText == null || keyText.isBlank()) {
                continue;
            }
            String valueText = block.relationships() == null ? null : block.relationships().stream()
                    .filter(relationship -> relationship.type() == RelationshipType.VALUE)
                    .flatMap(relationship -> relationship.ids().stream())
                    .map(blocksById::get)
                    .filter(Objects::nonNull)
                    .map(valueBlock -> collectChildWords(valueBlock, blocksById))
                    .filter(text -> text != null && !text.isBlank())
                    .findFirst()
                    .orElse(null);
            if (valueText != null) {
                fields.put(keyText.trim(), valueText.trim());
            }
        }
        return fields;
    }

    private String collectChildWords(Block block, Map<String, Block> blocksById) {
        if (block.relationships() == null) {
            return null;
        }
        return block.relationships().stream()
                .filter(relationship -> relationship.type() == RelationshipType.CHILD)
                .flatMap(relationship -> relationship.ids().stream())
                .map(blocksById::get)
                .filter(Objects::nonNull)
                .map(this::wordText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" "));
    }

    private String wordText(Block child) {
        if (child.blockType() == BlockType.WORD) {
            return child.text();
        }
        if (child.blockType() == BlockType.SELECTION_ELEMENT) {
            return child.selectionStatus() != null ? child.selectionStatus().toString() : null;
        }
        return null;
    }
}
