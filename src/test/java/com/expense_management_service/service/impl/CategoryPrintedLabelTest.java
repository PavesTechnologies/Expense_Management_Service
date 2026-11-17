package com.expense_management_service.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A category printed on the receipt outranks merchant-name keywords. */
class CategoryPrintedLabelTest {

    private final CategoryExtractor extractor = new CategoryExtractor();

    @Test
    void printedCategoryWinsOverAMerchantKeyword() {
        // "METRO MART" alone would read as transport; the receipt says Office Supplies.
        String receipt = "DEMO RECEIPT\nMETRO MART\nHyderabad, Telangana\nReceipt No: DEMO-003\nDate: 10 NOV 2026\n"
                + "Category: Office Supplies\nGroceries / essentials\nTOTAL Rs. 588.00";

        assertThat(extractor.extractFromText("METRO MART", receipt).value()).isEqualTo("OFFICE_SUPPLIES");
    }

    @Test
    void withoutAPrintedCategoryTheMerchantKeywordStillDecides() {
        assertThat(extractor.extractFromText("METRO MART", "METRO MART\nTOTAL Rs. 588.00").value()).isEqualTo("TRANSPORTATION");
    }

    @Test
    void aPrintedCategoryThatMatchesNothingFallsBackToTheOldRules() {
        assertThat(extractor.extractFromText("Hotel Taj", "Category: Miscellaneous\nHotel Taj room charges").value())
                .isEqualTo("ACCOMMODATION");
    }
}
