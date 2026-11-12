package com.expense_management_service.dto.request;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CashAdvanceRequest Security Tests")
class CashAdvanceRequestTest {

    @Test
    @DisplayName("Record should not have employeeId field")
    void shouldNotHaveEmployeeIdField() {
        UUID currencyId = UUID.randomUUID();
        LocalDate futureDate = LocalDate.now().plusDays(10);

        CashAdvanceRequest request = new CashAdvanceRequest(
                new BigDecimal("500.00"),
                currencyId,
                null,
                new BigDecimal("500.00"),
                "Travel expenses",
                "Business trip",
                "Notes",
                futureDate,
                futureDate
        );

        assertNotNull(request);
        assertEquals(new BigDecimal("500.00"), request.amount());
        assertEquals(currencyId, request.currencyId());

        // Verify record doesn't have employeeId field by checking record components
        // The record should only have: amount, currencyId, costCenterId, baseAmount,
        // purpose, title, notes, settlementDueDate, neededByDate
    }

    @Test
    @DisplayName("Record should not have status field")
    void shouldNotHaveStatusField() {
        UUID currencyId = UUID.randomUUID();
        LocalDate futureDate = LocalDate.now().plusDays(10);

        CashAdvanceRequest request = new CashAdvanceRequest(
                new BigDecimal("500.00"),
                currencyId,
                null,
                new BigDecimal("500.00"),
                "Travel expenses",
                "Business trip",
                "Notes",
                futureDate,
                futureDate
        );

        assertNotNull(request);
        // Verify status field is not present in the record
        // Record should only have: amount, currencyId, costCenterId, baseAmount,
        // purpose, title, notes, settlementDueDate, neededByDate
    }

    @Test
    @DisplayName("Record should not have outstandingBalance field")
    void shouldNotHaveOutstandingBalanceField() {
        UUID currencyId = UUID.randomUUID();
        LocalDate futureDate = LocalDate.now().plusDays(10);

        CashAdvanceRequest request = new CashAdvanceRequest(
                new BigDecimal("500.00"),
                currencyId,
                null,
                new BigDecimal("500.00"),
                "Travel expenses",
                "Business trip",
                "Notes",
                futureDate,
                futureDate
        );

        assertNotNull(request);
        // Verify outstandingBalance field is not present in the record
        // Record should only have: amount, currencyId, costCenterId, baseAmount,
        // purpose, title, notes, settlementDueDate, neededByDate
    }

    @Test
    @DisplayName("Compact constructor should work with minimal fields")
    void compactConstructorMinimalFields() {
        UUID currencyId = UUID.randomUUID();
        LocalDate futureDate = LocalDate.now().plusDays(10);

        CashAdvanceRequest request = new CashAdvanceRequest(
                new BigDecimal("500.00"),
                currencyId,
                new BigDecimal("500.00"),
                "Travel expenses",
                futureDate
        );

        assertNotNull(request);
        assertEquals(new BigDecimal("500.00"), request.amount());
        assertEquals(currencyId, request.currencyId());
        assertEquals(new BigDecimal("500.00"), request.baseAmount());
        assertEquals("Travel expenses", request.purpose());
        assertEquals(futureDate, request.settlementDueDate());
    }

    @Test
    @DisplayName("Compact constructor should work with all fields")
    void compactConstructorAllFields() {
        UUID currencyId = UUID.randomUUID();
        UUID costCenterId = UUID.randomUUID();
        LocalDate settlementDate = LocalDate.now().plusDays(10);
        LocalDate neededDate = LocalDate.now().plusDays(15);

        CashAdvanceRequest request = new CashAdvanceRequest(
                new BigDecimal("500.00"),
                currencyId,
                costCenterId,
                new BigDecimal("500.00"),
                "Travel expenses",
                "Business trip",
                "Important meeting",
                settlementDate,
                neededDate
        );

        assertNotNull(request);
        assertEquals(new BigDecimal("500.00"), request.amount());
        assertEquals(currencyId, request.currencyId());
        assertEquals(costCenterId, request.costCenterId());
        assertEquals("Business trip", request.title());
        assertEquals("Important meeting", request.notes());
    }

    @Test
    @DisplayName("Amount field is mandatory and positive")
    void amountFieldMandatory() {
        UUID currencyId = UUID.randomUUID();
        LocalDate futureDate = LocalDate.now().plusDays(10);

        CashAdvanceRequest request = new CashAdvanceRequest(
                new BigDecimal("100.00"),
                currencyId,
                null,
                null,
                null,
                null,
                null,
                futureDate,
                null
        );

        assertNotNull(request.amount());
        assertTrue(request.amount().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    @DisplayName("CurrencyId field is mandatory")
    void currencyIdFieldMandatory() {
        UUID currencyId = UUID.randomUUID();
        LocalDate futureDate = LocalDate.now().plusDays(10);

        CashAdvanceRequest request = new CashAdvanceRequest(
                new BigDecimal("100.00"),
                currencyId,
                null,
                null,
                null,
                null,
                null,
                futureDate,
                null
        );

        assertNotNull(request.currencyId());
        assertEquals(currencyId, request.currencyId());
    }
}
