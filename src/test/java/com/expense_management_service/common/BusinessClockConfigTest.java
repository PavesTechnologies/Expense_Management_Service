package com.expense_management_service.common;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessClockConfigTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant NOON_IST_28_OCT = LocalDate.of(2026, 10, 28).atTime(12, 0).atZone(IST).toInstant();

    @Test
    void production_usesTheRealCurrentDateInTheBusinessZone() {
        Clock clock = BusinessClockConfig.resolve(IST, false, "2026-11-05", "", NOON_IST_28_OCT);

        assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.now(IST));
        assertThat(clock.getZone()).isEqualTo(IST);
    }

    @Test
    void override_movesTheDateWhileTheClockKeepsRunning() {
        Clock clock = BusinessClockConfig.resolve(IST, true, "2026-11-05", "dev", NOON_IST_28_OCT);

        assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(clock.instant()).isAfter(NOON_IST_28_OCT.minusSeconds(1));
    }

    @Test
    void override_withABlankDateKeepsTheRealDate() {
        Clock clock = BusinessClockConfig.resolve(IST, true, "  ", "dev", NOON_IST_28_OCT);

        assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.now(IST));
    }

    @Test
    void override_isRefusedUnderTheProdProfile() {
        assertThatThrownBy(() -> BusinessClockConfig.resolve(IST, true, "2026-11-05", "prod,eu", NOON_IST_28_OCT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod profile");
    }
}
