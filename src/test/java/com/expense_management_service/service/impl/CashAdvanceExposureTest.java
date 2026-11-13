package com.expense_management_service.service.impl;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class CashAdvanceExposureTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void specExample_outstandingNineThousand_requestTenThousand_limitFifteenThousand_flagsFourThousandOver() {
        CashAdvanceExposure.Result r = CashAdvanceExposure.evaluate(d("15000"), d("9000"), d("0"), d("10000"));

        assertTrue(r.limitConfigured());
        assertEquals(0, d("19000").compareTo(r.potential()));
        assertEquals(0, d("4000").compareTo(r.excessOverLimit()));
        assertTrue(r.exceedsLimit());
    }

    @Test
    void withinLimit_isNotFlagged() {
        CashAdvanceExposure.Result r = CashAdvanceExposure.evaluate(d("15000"), d("3000"), d("0"), d("8000"));

        assertEquals(0, d("11000").compareTo(r.potential()));
        assertEquals(0, BigDecimal.ZERO.compareTo(r.excessOverLimit()));
        assertFalse(r.exceedsLimit());
    }

    @Test
    void exactlyAtLimit_isNotFlagged() {
        CashAdvanceExposure.Result r = CashAdvanceExposure.evaluate(d("15000"), d("5000"), d("0"), d("10000"));

        assertFalse(r.exceedsLimit());
    }

    @Test
    void committedNotDisbursedCountsTowardExposure() {
        CashAdvanceExposure.Result r = CashAdvanceExposure.evaluate(d("15000"), d("3000"), d("6000"), d("8000"));

        assertEquals(0, d("17000").compareTo(r.potential()));
        assertTrue(r.exceedsLimit());
    }

    @Test
    void noLimitConfigured_nothingFlaggedButFiguresStillReturned() {
        CashAdvanceExposure.Result r = CashAdvanceExposure.evaluate(null, d("9000"), d("0"), d("10000"));

        assertFalse(r.limitConfigured());
        assertNull(r.limit());
        assertFalse(r.exceedsLimit());
        assertEquals(0, d("19000").compareTo(r.potential()));
    }

    @Test
    void nullAmountsAreTreatedAsZero() {
        CashAdvanceExposure.Result r = CashAdvanceExposure.evaluate(d("15000"), null, null, null);

        assertEquals(0, BigDecimal.ZERO.compareTo(r.potential()));
        assertFalse(r.exceedsLimit());
    }

    @Test
    void parseLimit_blankZeroNegativeOrMalformed_meansNoLimit() {
        assertNull(CashAdvanceExposure.parseLimit(null));
        assertNull(CashAdvanceExposure.parseLimit("   "));
        assertNull(CashAdvanceExposure.parseLimit("0"));
        assertNull(CashAdvanceExposure.parseLimit("-5"));
        assertNull(CashAdvanceExposure.parseLimit("fifteen thousand"));
        assertEquals(0, d("15000").compareTo(CashAdvanceExposure.parseLimit(" 15000 ")));
    }
}
