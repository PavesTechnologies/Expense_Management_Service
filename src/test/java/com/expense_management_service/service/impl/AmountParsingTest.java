package com.expense_management_service.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Amounts as printed on receipts, including the currency prefix that used to break parsing. */
class AmountParsingTest {

    @Test
    void rsPrefixedAmountIsRead() {
        // "TOTAL Rs. 357.00" on the METRO MART bill: the dot of "Rs." must not break the number.
        assertThat(ReceiptFieldParsingUtils.parseAmount("Rs. 357.00", "TOTAL")).isEqualByComparingTo("357.00");
        assertThat(ReceiptFieldParsingUtils.parseAmount("Rs. 17.00", "TAX")).isEqualByComparingTo("17.00");
    }

    @Test
    void otherCurrencyPrefixesAndThousandsSeparatorsAreRead() {
        assertThat(ReceiptFieldParsingUtils.parseAmount("₹1,234.50", "TOTAL")).isEqualByComparingTo("1234.50");
        assertThat(ReceiptFieldParsingUtils.parseAmount("INR 588", "TOTAL")).isEqualByComparingTo("588");
        assertThat(ReceiptFieldParsingUtils.parseAmount("357.00", "TOTAL")).isEqualByComparingTo("357.00");
    }

    @Test
    void textWithoutAnAmountGivesNull() {
        assertThat(ReceiptFieldParsingUtils.parseAmount("Thank you", "TOTAL")).isNull();
        assertThat(ReceiptFieldParsingUtils.parseAmount(null, "TOTAL")).isNull();
    }
}
