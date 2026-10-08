package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns the OCR category hint ({@code ReceiptOcr.category}, e.g. "TRAVEL") into one of the
 * admin-configured active {@link ExpenseCategory} rows, so the review screen can pre-select it.
 * <p>
 * Resolved at read time rather than stored: categories are renamed, added and retired by admins,
 * and the suggestion should follow whatever is active when the employee opens the review.
 * Matching is by words in the category's name or code — each hint lists the words it accepts in
 * priority order, and the first word that any active category carries wins. A word of four or more
 * letters also matches as a prefix ("meal" → "Meals", "transport" → "Transportation"); shorter
 * ones must match a whole word, so "air" never matches "Repairs".
 */
@Component
@RequiredArgsConstructor
public class OcrCategoryResolver {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private static final Map<String, List<String>> HINT_WORDS = Map.ofEntries(
            Map.entry("TRAVEL", List.of("travel", "airfare", "air", "flight", "train", "rail", "bus", "transport", "conveyance")),
            Map.entry("ACCOMMODATION", List.of("accommodation", "lodging", "hotel", "stay")),
            Map.entry("MEALS", List.of("meal", "food", "dining", "refreshment", "restaurant", "beverage")),
            Map.entry("TRANSPORTATION", List.of("conveyance", "local", "cab", "taxi", "fuel", "transport", "travel")),
            Map.entry("OFFICE_SUPPLIES", List.of("office", "stationery", "stationary", "supplies", "printing")),
            Map.entry("EQUIPMENT", List.of("equipment", "hardware", "computer", "electronic", "it")),
            Map.entry("ENTERTAINMENT", List.of("entertainment", "recreation")),
            Map.entry("UTILITIES", List.of("utility", "utilities", "internet", "telephone", "phone", "mobile", "communication", "broadband")),
            Map.entry("HEALTHCARE", List.of("medical", "health", "healthcare", "wellness")),
            Map.entry("PROFESSIONAL_SERVICES", List.of("professional", "consulting", "consultancy", "legal")),
            Map.entry("INSURANCE", List.of("insurance")));

    private final ExpenseCategoryRepository expenseCategoryRepository;

    /** The active category the hint points to, or {@code null} if none fits (or no hint). */
    public ExpenseCategory resolve(String hint) {
        if (hint == null) {
            return null;
        }
        List<String> wanted = HINT_WORDS.get(hint.toUpperCase(Locale.ROOT));
        if (wanted == null) {
            return null;
        }
        LocalDate today = LocalDate.now();
        List<ExpenseCategory> active = expenseCategoryRepository.findByStatusIgnoreCaseOrderByCategoryNameAsc(STATUS_ACTIVE)
                .stream()
                .filter(category -> category.getEffectiveTo() == null || !category.getEffectiveTo().isBefore(today))
                .toList();
        for (String word : wanted) {
            for (ExpenseCategory category : active) {
                if (words(category).stream().anyMatch(token -> matches(token, word))) {
                    return category;
                }
            }
        }
        return null;
    }

    private static boolean matches(String token, String word) {
        return token.equals(word) || (word.length() >= 4 && token.startsWith(word));
    }

    private static Set<String> words(ExpenseCategory category) {
        String text = (category.getCategoryName() + " " + category.getCategoryCode()).toLowerCase(Locale.ROOT);
        return Arrays.stream(text.split("[^\\p{L}\\p{N}]+"))
                .filter(token -> !token.isEmpty())
                .collect(Collectors.toSet());
    }
}
