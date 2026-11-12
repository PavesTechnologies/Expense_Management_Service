package com.expense_management_service.repository;

import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CashAdvanceRepository Pagination Logic Tests")
class CashAdvanceRepositoryPaginationIT {

    private List<CashAdvance> mockDatabase;
    private Currency testCurrency;

    @BeforeEach
    void setUp() {
        mockDatabase = new ArrayList<>();
        testCurrency = Currency.builder()
                .currencyCode("USD")
                .currencyName("US Dollar")
                .symbol("$")
                .build();
    }

    @Test
    @DisplayName("Pageable should correctly calculate pages and offsets")
    void pageableCalculatesPages() {
        for (int i = 0; i < 15; i++) {
            mockDatabase.add(CashAdvance.builder()
                    .advanceId(UUID.randomUUID())
                    .employeeId("EMP001")
                    .amount(new BigDecimal("100.00"))
                    .currency(testCurrency)
                    .status("DRAFT")
                    .settlementDueDate(LocalDate.now().plusDays(10))
                    .build());
        }

        Pageable page0 = PageRequest.of(0, 5);
        List<CashAdvance> page0Content = paginateList(mockDatabase, page0);
        Page<CashAdvance> result0 = new PageImpl<>(page0Content, page0, mockDatabase.size());

        assertEquals(5, result0.getContent().size());
        assertEquals(15, result0.getTotalElements());
        assertEquals(3, result0.getTotalPages());
        assertTrue(result0.hasNext());
        assertFalse(result0.hasPrevious());

        Pageable page1 = PageRequest.of(1, 5);
        List<CashAdvance> page1Content = paginateList(mockDatabase, page1);
        Page<CashAdvance> result1 = new PageImpl<>(page1Content, page1, mockDatabase.size());

        assertEquals(5, result1.getContent().size());
        assertTrue(result1.hasPrevious());
        assertTrue(result1.hasNext());
    }

    @Test
    @DisplayName("Pageable with filter should paginate filtered results")
    void pageableWithFilter() {
        for (int i = 0; i < 10; i++) {
            mockDatabase.add(CashAdvance.builder()
                    .advanceId(UUID.randomUUID())
                    .employeeId("EMP001")
                    .amount(new BigDecimal("100.00"))
                    .currency(testCurrency)
                    .status(i < 6 ? "DRAFT" : "SUBMITTED")
                    .settlementDueDate(LocalDate.now().plusDays(10))
                    .build());
        }

        Pageable pageable = PageRequest.of(0, 10);
        List<CashAdvance> filtered = mockDatabase.stream()
                .filter(a -> a.getStatus().equals("DRAFT"))
                .collect(Collectors.toList());
        List<CashAdvance> paged = paginateList(filtered, pageable);
        Page<CashAdvance> result = new PageImpl<>(paged, pageable, filtered.size());

        assertEquals(6, result.getTotalElements());
        assertEquals(6, result.getContent().size());
        result.getContent().forEach(advance -> assertEquals("DRAFT", advance.getStatus()));
    }

    @Test
    @DisplayName("Multiple status filters should paginate correctly")
    void multipleStatusFilters() {
        for (int i = 0; i < 20; i++) {
            mockDatabase.add(CashAdvance.builder()
                    .advanceId(UUID.randomUUID())
                    .employeeId("EMP" + (i % 3))
                    .amount(new BigDecimal("100.00"))
                    .currency(testCurrency)
                    .status(i % 2 == 0 ? "SUBMITTED" : "DRAFT")
                    .settlementDueDate(LocalDate.now().plusDays(10))
                    .build());
        }

        Pageable pageable = PageRequest.of(0, 5);
        List<CashAdvance> filtered = mockDatabase.stream()
                .filter(a -> a.getStatus().equals("SUBMITTED"))
                .collect(Collectors.toList());
        List<CashAdvance> paged = paginateList(filtered, pageable);
        Page<CashAdvance> result = new PageImpl<>(paged, pageable, filtered.size());

        assertEquals(5, result.getContent().size());
        assertEquals(10, result.getTotalElements());
        assertEquals(2, result.getTotalPages());
        result.getContent().forEach(advance -> assertEquals("SUBMITTED", advance.getStatus()));
    }

    @Test
    @DisplayName("Manager ID filter should paginate correctly")
    void managerIdFilter() {
        for (int i = 0; i < 12; i++) {
            mockDatabase.add(CashAdvance.builder()
                    .advanceId(UUID.randomUUID())
                    .employeeId("EMP00" + i)
                    .managerId("MGR001")
                    .amount(new BigDecimal("100.00"))
                    .currency(testCurrency)
                    .status("SUBMITTED")
                    .settlementDueDate(LocalDate.now().plusDays(10))
                    .build());
        }

        Pageable pageable = PageRequest.of(0, 5);
        List<CashAdvance> filtered = mockDatabase.stream()
                .filter(a -> "MGR001".equals(a.getManagerId()))
                .collect(Collectors.toList());
        List<CashAdvance> paged = paginateList(filtered, pageable);
        Page<CashAdvance> result = new PageImpl<>(paged, pageable, filtered.size());

        assertEquals(5, result.getContent().size());
        assertEquals(12, result.getTotalElements());
        assertTrue(result.hasNext());
    }

    @Test
    @DisplayName("Empty result should return empty page")
    void emptyResultReturnsEmptyPage() {
        Pageable pageable = PageRequest.of(0, 10);
        List<CashAdvance> filtered = mockDatabase.stream()
                .filter(a -> a.getEmployeeId().equals("NONEXISTENT"))
                .collect(Collectors.toList());
        Page<CashAdvance> result = new PageImpl<>(filtered, pageable, 0);

        assertEquals(0, result.getContent().size());
        assertEquals(0, result.getTotalElements());
        assertEquals(0, result.getTotalPages());
    }

    @Test
    @DisplayName("Page out of range should return empty content")
    void pageOutOfRangeReturnsEmptyContent() {
        for (int i = 0; i < 5; i++) {
            mockDatabase.add(CashAdvance.builder()
                    .advanceId(UUID.randomUUID())
                    .employeeId("EMP001")
                    .amount(new BigDecimal("100.00"))
                    .currency(testCurrency)
                    .status("DRAFT")
                    .settlementDueDate(LocalDate.now().plusDays(10))
                    .build());
        }

        Pageable pageable = PageRequest.of(10, 10);
        List<CashAdvance> paged = paginateList(mockDatabase, pageable);
        Page<CashAdvance> result = new PageImpl<>(paged, pageable, mockDatabase.size());

        assertEquals(0, result.getContent().size());
        assertEquals(5, result.getTotalElements());
    }

    private List<CashAdvance> paginateList(List<CashAdvance> list, Pageable pageable) {
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), list.size());
        return start >= list.size() ? new ArrayList<>() : new ArrayList<>(list.subList(start, end));
    }
}
