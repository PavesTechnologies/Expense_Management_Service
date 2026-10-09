package com.expense_management_service.service.impl;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.ExpenseField;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Classifies a receipt into a coarse category hint (TRAVEL, MEALS, ...) from its text. The hint is
 * not an {@code ExpenseCategory} — categories are admin-configured per tenant, so the hint is
 * resolved to a real active category at read time by {@code OcrCategoryResolver}.
 * <p>
 * Scores the whole receipt, not just the merchant name: a merchant like "KSRTC" carries no keyword
 * on its own, but the body ("Passenger Ticket", "Journey Date", "Bus No", "Fare") clearly does.
 * Keywords are matched on word boundaries so "inn" never fires on "dinner" or "bar" on "barcode".
 * Merchant hits weigh more than body hits; the highest-scoring hint wins, and a single weak body
 * hit is not enough to suggest anything.
 */
@Slf4j
final class CategoryExtractor {

    private static final int MERCHANT_HIT_WEIGHT = 3;
    private static final int BODY_HIT_WEIGHT = 1;
    private static final int MIN_SCORE = 2;

    /** Declaration order breaks ties. */
    private static final Map<String, List<Pattern>> CATEGORY_KEYWORDS = new LinkedHashMap<>();

    static {
        register("TRAVEL",
                "airline", "airlines", "airways", "flight", "airport", "boarding pass", "boarding point", "pnr",
                "irctc", "railway", "railways", "train", "coach", "bus", "bus no", "passenger", "passenger ticket",
                "journey", "journey date", "fare", "base fare", "berth", "seat no", "departure", "arrival",
                "ksrtc", "msrtc", "tsrtc", "apsrtc", "tnstc", "rtc", "redbus", "makemytrip", "goibibo", "cleartrip",
                "indigo", "air india", "vistara", "akasa", "spicejet", "car rental", "hertz", "avis");
        register("ACCOMMODATION",
                "hotel", "motel", "resort", "airbnb", "oyo", "hostel", "guest house", "lodge", "lodging", "inn",
                "accommodation", "room charges", "room rent", "room no", "check-in", "check in", "night stay");
        register("MEALS",
                "restaurant", "cafe", "café", "coffee", "pizza", "burger", "food", "foods", "lunch", "dinner",
                "breakfast", "bakery", "diner", "bistro", "pub", "grill", "kitchen", "dhaba", "biryani", "swiggy",
                "zomato", "mcdonald's", "mcdonalds", "kfc", "subway", "starbucks", "domino's", "dominos",
                "dine in", "takeaway", "table no", "kot");
        register("TRANSPORTATION",
                "taxi", "cab", "uber", "ola", "rapido", "lyft", "grab", "auto rickshaw", "parking", "toll", "fastag",
                "fuel", "petrol", "diesel", "gas station", "filling station", "hpcl", "bpcl", "iocl", "indian oil",
                "metro", "valet");
        register("OFFICE_SUPPLIES",
                "stationery", "stationary", "office supplies", "toner", "ink cartridge", "stapler", "staples",
                "officeworks", "a4 paper", "printing");
        register("EQUIPMENT",
                "laptop", "desktop", "monitor", "keyboard", "mouse", "headset", "headphones", "webcam", "camera",
                "microphone", "electronics", "croma", "reliance digital", "hard disk", "ssd", "charger");
        register("ENTERTAINMENT",
                "movie", "cinema", "theatre", "theater", "concert", "pvr", "inox", "bookmyshow", "amusement",
                "museum");
        register("UTILITIES",
                "electricity", "internet", "broadband", "wifi", "postpaid", "recharge", "airtel", "jio", "vodafone",
                "bsnl", "telecom", "mobile bill", "data pack");
        register("HEALTHCARE",
                "pharmacy", "chemist", "medical", "medicine", "medicines", "hospital", "clinic", "doctor", "dental",
                "dentist", "medplus", "diagnostic", "diagnostics");
        register("PROFESSIONAL_SERVICES",
                "consultant", "consulting", "consultancy", "legal", "advocate", "lawyer", "attorney",
                "chartered accountant", "audit", "professional fees");
        register("INSURANCE",
                "insurance", "premium", "policy no", "policy number");
    }

    private static void register(String category, String... keywords) {
        List<Pattern> patterns = new ArrayList<>(keywords.length);
        for (String keyword : keywords) {
            // Whitespace inside a keyword matches any run of whitespace; an optional plural "s".
            String body = Pattern.quote(keyword).replace(" ", "\\E\\s+\\Q");
            patterns.add(Pattern.compile("(?<![\\p{L}\\p{N}])" + body + "s?(?![\\p{L}\\p{N}])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
        CATEGORY_KEYWORDS.put(category, List.copyOf(patterns));
    }

    /** AnalyzeExpense: merchant plus every summary field's label/value and every OCR LINE. */
    ExtractionResult<String> extract(ExpenseFieldIndex index, String merchantName) {
        List<String> lines = new ArrayList<>();
        for (ExpenseField field : index.allFields()) {
            if (field.labelDetection() != null) lines.add(field.labelDetection().text());
            if (field.valueDetection() != null) lines.add(field.valueDetection().text());
        }
        for (Block block : index.blocks()) {
            if (block.blockType() == BlockType.LINE) lines.add(block.text());
        }
        return extractFromText(merchantName, lines.stream().filter(Objects::nonNull).collect(Collectors.joining("\n")));
    }

    /**
     * @param merchantName the merchant/vendor as extracted, or {@code null}
     * @param receiptText  the receipt's full text (any line separator), or {@code null}
     * @return the best category hint with a 0-1 confidence, or empty if nothing scored enough
     */
    ExtractionResult<String> extractFromText(String merchantName, String receiptText) {
        String merchant = merchantName == null ? "" : merchantName;
        String body = receiptText == null ? "" : receiptText;
        if (merchant.isBlank() && body.isBlank()) {
            return ExtractionResult.empty();
        }

        String best = null;
        int bestScore = 0;
        for (Map.Entry<String, List<Pattern>> entry : CATEGORY_KEYWORDS.entrySet()) {
            int score = 0;
            for (Pattern keyword : entry.getValue()) {
                if (keyword.matcher(merchant).find()) {
                    score += MERCHANT_HIT_WEIGHT;
                } else if (keyword.matcher(body).find()) {
                    score += BODY_HIT_WEIGHT;
                }
            }
            if (score > bestScore) {
                best = entry.getKey();
                bestScore = score;
            }
        }

        if (best == null || bestScore < MIN_SCORE) {
            log.debug("[OCR] No category matched for merchant: {} (best score {})", merchantName, bestScore);
            return ExtractionResult.empty();
        }
        BigDecimal confidence = BigDecimal.valueOf(Math.min(0.95, 0.5 + 0.1 * bestScore)).setScale(2, RoundingMode.HALF_UP);
        log.debug("[OCR] Category hint {} for merchant: {} (score {})", best, merchantName, bestScore);
        return ExtractionResult.of(best, confidence);
    }
}
