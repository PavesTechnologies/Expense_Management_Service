package com.expense_management_service.repository;

import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@DisplayName("CashAdvanceRepository Pagination Integration Tests")
class CashAdvanceRepositoryPaginationIT {

    @Autowired
    private CashAdvanceRepository cashAdvanceRepository;

    @Autowired
    private CurrencyRepository currencyRepository;

    @Test
    @DisplayName("findByEmployeeId with Pageable should return paged results")
    void findByEmployeeIdWithPagination() {
        Currency currency = createAndSaveCurrency();

        for (int i = 0; i < 15; i++) {
            CashAdvance advance = CashAdvance.builder()
                    .employeeId("EMP001")
                    .amount(new BigDecimal("100.00"))
                    .currency(currency)
                    .status("DRAFT")
                    .settlementDueDate(LocalDate.now().plusDays(10))
                    .build();
            cashAdvanceRepository.save(advance);
        }

        Pageable page1 = PageRequest.of(0, 5);
        Page<CashAdvance> result1 = cashAdvanceRepository.findByEmployeeId("EMP001", page1);

        assertEquals(5, result1.getContent().size());
        assertEquals(15, result1.getTotalElements());
        assertEquals(3, result1.getTotalPages());
        assertTrue(result1.hasNext());
        assertFalse(result1.hasPrevious());

        Pageable page2 = PageRequest.of(1, 5);
        Page<CashAdvance> result2 = cashAdvanceRepository.findByEmployeeId("EMP001", page2);

        assertEquals(5, result2.getContent().size());
        assertEquals(15, result2.getTotalElements());
        assertTrue(result2.hasPrevious());
    }

    @Test
    @DisplayName("findByEmployeeIdAndStatus with Pageable should filter and paginate")
    void findByEmployeeIdAndStatusWithPagination() {
        Currency currency = createAndSaveCurrency();

        for (int i = 0; i < 10; i++) {
            CashAdvance advance = CashAdvance.builder()
                    .employeeId("EMP001")
                    .amount(new BigDecimal("100.00"))
                    .currency(currency)
                    .status(i < 6 ? "DRAFT" : "SUBMITTED")
                    .settlementDueDate(LocalDate.now().plusDays(10))
                    .build();
            cashAdvanceRepository.save(advance);
        }

        Pageable pageable = PageRequest.of(0, 10);
        Page<CashAdvance> result = cashAdvanceRepository.findByEmployeeIdAndStatus("EMP001", "DRAFT", pageable);

        assertEquals(6, result.getTotalElements());
        assertEquals(6, result.getContent().size());
        result.getContent().forEach(advance -> assertEquals("DRAFT", advance.getStatus()));
    }

    @Test
    @DisplayName("findByStatus with Pageable should return paged status-filtered results")
    void findByStatusWithPagination() {
        Currency currency = createAndSaveCurrency();

        for (int i = 0; i < 20; i++) {
            CashAdvance advance = CashAdvance.builder()
                    .employeeId("EMP" + (i % 3))
                    .amount(new BigDecimal("100.00"))
                    .currency(currency)
                    .status(i % 2 == 0 ? "SUBMITTED" : "DRAFT")
                    .settlementDueDate(LocalDate.now().plusDays(10))
                    .build();
            cashAdvanceRepository.save(advance);
        }

        Pageable pageable = PageRequest.of(0, 5);
        Page<CashAdvance> result = cashAdvanceRepository.findByStatus("SUBMITTED", pageable);

        assertEquals(5, result.getContent().size());
        assertEquals(10, result.getTotalElements());
        assertEquals(2, result.getTotalPages());
        result.getContent().forEach(advance -> assertEquals("SUBMITTED", advance.getStatus()));
    }

    @Test
    @DisplayName("findByManagerId with Pageable should return paged results")
    void findByManagerIdWithPagination() {
        Currency currency = createAndSaveCurrency();

        for (int i = 0; i < 12; i++) {
            CashAdvance advance = CashAdvance.builder()
                    .employeeId("EMP00" + i)
                    .managerId("MGR001")
                    .amount(new BigDecimal("100.00"))
                    .currency(currency)
                    .status("SUBMITTED")
                    .settlementDueDate(LocalDate.now().plusDays(10))
                    .build();
            cashAdvanceRepository.save(advance);
        }

        Pageable pageable = PageRequest.of(0, 5);
        Page<CashAdvance> result = cashAdvanceRepository.findByManagerId("MGR001", pageable);

        assertEquals(5, result.getContent().size());
        assertEquals(12, result.getTotalElements());
        assertTrue(result.hasNext());
    }

    @Test
    @DisplayName("Empty result should return empty page")
    void emptyResultReturnsEmptyPage() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<CashAdvance> result = cashAdvanceRepository.findByEmployeeId("NONEXISTENT", pageable);

        assertEquals(0, result.getContent().size());
        assertEquals(0, result.getTotalElements());
        assertEquals(0, result.getTotalPages());
    }

    @Test
    @DisplayName("Page out of range should return empty content")
    void pageOutOfRangeReturnsEmptyContent() {
        Currency currency = createAndSaveCurrency();

        for (int i = 0; i < 5; i++) {
            CashAdvance advance = CashAdvance.builder()
                    .employeeId("EMP001")
                    .amount(new BigDecimal("100.00"))
                    .currency(currency)
                    .status("DRAFT")
                    .settlementDueDate(LocalDate.now().plusDays(10))
                    .build();
            cashAdvanceRepository.save(advance);
        }

        Pageable pageable = PageRequest.of(10, 10); // Page 10 (out of range)
        Page<CashAdvance> result = cashAdvanceRepository.findByEmployeeId("EMP001", pageable);

        assertEquals(0, result.getContent().size());
        assertEquals(5, result.getTotalElements());
    }

    private Currency createAndSaveCurrency() {
        Currency currency = Currency.builder()
                .currencyCode("USD")
                .currencyName("US Dollar")
                .symbol("$")
                .build();
        return currencyRepository.save(currency);
    }
}
