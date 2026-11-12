package com.expense_management_service.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryExtractorTest {

    private final CategoryExtractor extractor = new CategoryExtractor();

    @Test
    void busTicket_isTravel_evenWhenMerchantNameHasNoKeyword() {
        String text = """
                KSRTC Journey Redefined
                Karnataka State Road Transport Corporation
                PASSENGER TICKET
                Ticket No : KAS2509156789
                Journey Date : 15 Sep 2025
                From : Bengaluru (Majestic)
                Service Type : Airavat (Volvo)
                Bus No : KA-01-F-2345
                Seat No : 12
                Fare Type : Adult
                Base Fare : 350.00
                GST (5%) : 17.50
                Total Amount : 367.50
                Payment Mode : UPI
                """;
        assertThat(extractor.extractFromText("KSRTC", text).value()).isEqualTo("TRAVEL");
    }

    @Test
    void restaurantBill_isMeals() {
        String text = "Table No 4\nPaneer Tikka 280.00\nCGST 2.5%\nSGST 2.5%\nThank you, visit again";
        assertThat(extractor.extractFromText("Udupi Grand Restaurant", text).value()).isEqualTo("MEALS");
    }

    @Test
    void keywordsMatchWholeWordsOnly() {
        // "dinner" must not fire ACCOMMODATION's "inn", "barcode" must not fire anything.
        assertThat(extractor.extractFromText(null, "Dinner buffet\nLunch combo\nbarcode").value()).isEqualTo("MEALS");
        assertThat(extractor.extractFromText("Acme Traders", "Item 1 100.00\nScan barcode").isPresent()).isFalse();
    }

    @Test
    void singleWeakBodyHit_suggestsNothing() {
        assertThat(extractor.extractFromText("Acme Traders", "Thank you for your purchase\nmonitor stand").isPresent()).isFalse();
    }
}
