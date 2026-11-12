package com.expense_management_service.enums;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ItcStatusTest {

    @Test
    void noRecoverableTax_isNotRecoverable_whateverTheFinanceStatus() {
        assertThat(ItcStatus.of(null, TaxValidationStatus.FINANCE_VERIFIED)).isEqualTo(ItcStatus.NOT_RECOVERABLE);
        assertThat(ItcStatus.of(BigDecimal.ZERO, TaxValidationStatus.FINANCE_ADJUSTED)).isEqualTo(ItcStatus.NOT_RECOVERABLE);
    }

    @Test
    void recoverableTax_isVerifiedOnlyOnceFinanceVerifiedOrAdjustedIt() {
        BigDecimal recoverable = new BigDecimal("90.00");
        assertThat(ItcStatus.of(recoverable, TaxValidationStatus.FINANCE_VERIFIED)).isEqualTo(ItcStatus.VERIFIED);
        assertThat(ItcStatus.of(recoverable, TaxValidationStatus.FINANCE_ADJUSTED)).isEqualTo(ItcStatus.VERIFIED);
        assertThat(ItcStatus.of(recoverable, TaxValidationStatus.MATCHED)).isEqualTo(ItcStatus.UNVERIFIED);
        assertThat(ItcStatus.of(recoverable, TaxValidationStatus.REQUIRES_FINANCE_REVIEW)).isEqualTo(ItcStatus.UNVERIFIED);
        assertThat(ItcStatus.of(recoverable, null)).isEqualTo(ItcStatus.UNVERIFIED);
    }

    @Test
    void rejectedReports_areLeftOutOfTaxReporting_alongWithDraftsAndCancelled() {
        assertThat(ReportStatus.excludedFromTaxReporting())
                .containsExactlyInAnyOrder(ReportStatus.DRAFT, ReportStatus.CANCELLED, ReportStatus.REJECTED);
    }
}
