package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.CashAdvanceRequest;
import com.expense_management_service.entity.CashAdvance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CashAdvanceMapperExpectedTripDatesTest {

    private final CashAdvanceMapper mapper = new CashAdvanceMapper();

    private CashAdvanceRequest request(LocalDate tripStart, LocalDate tripEnd) {
        return new CashAdvanceRequest(new BigDecimal("5000.00"), UUID.randomUUID(), null, null, "Client visit",
                "Client visit advance", null, LocalDate.of(2026, 11, 15), LocalDate.of(2026, 10, 10), tripStart, tripEnd);
    }

    @Test
    void toEntity_keepsTheExpectedTripDates_separateFromNeededByAndSettlementDates() {
        CashAdvance entity = mapper.toEntity(request(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 20)));

        assertThat(entity.getExpectedTripStartDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(entity.getExpectedTripEndDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(entity.getNeededByDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(entity.getSettlementDueDate()).isEqualTo(LocalDate.of(2026, 11, 15));
    }

    @Test
    void toEntity_allowsMissingExpectedTripDates() {
        CashAdvance entity = mapper.toEntity(request(null, null));

        assertThat(entity.getExpectedTripStartDate()).isNull();
        assertThat(entity.getExpectedTripEndDate()).isNull();
    }

    @Test
    void toEntity_refusesAnExpectedTripEndBeforeItsStart() {
        assertThatThrownBy(() -> mapper.toEntity(request(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 15))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected trip end date cannot be before");
    }

    @Test
    void toEntity_acceptsAOneDayTrip() {
        CashAdvance entity = mapper.toEntity(request(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 15)));

        assertThat(entity.getExpectedTripEndDate()).isEqualTo(entity.getExpectedTripStartDate());
    }
}
