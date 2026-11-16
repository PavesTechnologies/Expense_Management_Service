package com.expense_management_service.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.expense_management_service.dto.response.ApprovalRecordResponse;
import com.expense_management_service.dto.response.CashAdvanceResponse;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.dto.response.PageResponse;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.service.ApprovalRecordsService;
import com.expense_management_service.service.ApprovalWorkflowService;
import com.expense_management_service.service.CashAdvanceService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * The approver's merged record list: one date order across expenses and cash advances, cut into pages after the
 * merge, with the tab and search filters applied to both record types.
 */
class ApprovalRecordsServiceTest {

    private static final String APPROVER = "5100023";
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 1, 9, 0);

    private ApprovalWorkflowService workflow;
    private CashAdvanceService cash;
    private ApprovalRecordsService service;

    @BeforeEach
    void setUp() {
        workflow = mock(ApprovalWorkflowService.class);
        cash = mock(CashAdvanceService.class);
        ExpenseReportRepository repo = mock(ExpenseReportRepository.class);
        service = new ApprovalRecordsService(workflow, cash, repo);
        when(repo.findAllById(any())).thenReturn(List.of());
        when(workflow.getMyQueue(anyString(), any())).thenReturn(queue(List.of()));
        when(workflow.getMyHistory(anyString(), any(), any())).thenReturn(history(List.of()));
        when(cash.getMyApprovals(anyString(), any(), any())).thenReturn(new PageImpl<>(List.of()));
    }

    @Test
    void expensesAndCashAdvancesAreInterleavedNewestFirst() {
        when(workflow.getMyHistory(anyString(), any(), any())).thenReturn(history(List.of(
                report("EXP-OLD", 1), report("EXP-NEW", 5))));
        when(cash.getMyApprovals(anyString(), any(), any())).thenReturn(new PageImpl<>(List.of(
                advance(3, "APPROVED"))));

        PageResponse<ApprovalRecordResponse> page = service.page(APPROVER, true, "approved", null, 0, 20);

        assertThat(page.content()).extracting(ApprovalRecordResponse::reference)
                .containsExactly("EXP-NEW", advanceRef(3), "EXP-OLD");
        assertThat(page.content()).extracting(ApprovalRecordResponse::type)
                .containsExactly(ApprovalRecordResponse.EXPENSE, ApprovalRecordResponse.CASH_ADVANCE, ApprovalRecordResponse.EXPENSE);
    }

    @Test
    void pagesCoverEveryRecordOnceAcrossBothTypes() {
        List<ExpenseReportResponse> reports = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            reports.add(report("EXP-" + i, i));
        }
        List<CashAdvanceResponse> advances = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            advances.add(advance(i, "APPROVED"));
        }
        when(workflow.getMyHistory(anyString(), any(), any())).thenReturn(history(reports));
        when(cash.getMyApprovals(anyString(), any(), any())).thenReturn(new PageImpl<>(advances));

        int size = 20;
        Set<String> seen = new HashSet<>();
        int pages = 0;
        PageResponse<ApprovalRecordResponse> page;
        do {
            page = service.page(APPROVER, true, "approved", null, pages, size);
            for (ApprovalRecordResponse r : page.content()) {
                assertThat(seen.add(r.type() + ":" + r.id())).as("duplicate on page " + pages).isTrue();
            }
            pages++;
        } while (!page.last());

        assertThat(page.totalElements()).isEqualTo(147);
        assertThat(seen).hasSize(147);
        assertThat(pages).isEqualTo(8);
    }

    @Test
    void cashAdvancesBeyondTheOldHundredRecordLimitAreReachable() {
        List<CashAdvanceResponse> advances = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            advances.add(advance(i, "APPROVED"));
        }
        when(cash.getMyApprovals(anyString(), any(), any())).thenReturn(new PageImpl<>(advances));

        PageResponse<ApprovalRecordResponse> last = service.page(APPROVER, true, "approved", null, 7, 20);

        assertThat(last.totalElements()).isEqualTo(150);
        assertThat(last.content()).hasSize(10);
    }

    @Test
    void cashAdvancesAreLeftOutForRolesThatCannotSeeThem() {
        when(cash.getMyApprovals(anyString(), any(), any())).thenReturn(new PageImpl<>(List.of(advance(1, "APPROVED"))));

        PageResponse<ApprovalRecordResponse> page = service.page(APPROVER, false, "approved", null, 0, 20);

        assertThat(page.content()).noneMatch(r -> ApprovalRecordResponse.CASH_ADVANCE.equals(r.type()));
    }

    @Test
    void tabFiltersApplyToBothTypes() {
        when(workflow.getMyHistory(anyString(), eq("APPROVED"), any())).thenReturn(history(List.of(report("EXP-A", 2))));
        when(workflow.getMyHistory(anyString(), eq("REJECTED"), any())).thenReturn(history(List.of()));
        when(cash.getMyApprovals(anyString(), any(), any())).thenReturn(new PageImpl<>(List.of(
                advance(3, "REJECTED"), advance(4, "APPROVED"))));

        PageResponse<ApprovalRecordResponse> rejected = service.page(APPROVER, true, "rejected", null, 0, 20);

        assertThat(rejected.content()).extracting(ApprovalRecordResponse::reference).containsExactly(advanceRef(3));
    }

    @Test
    void searchMatchesEitherType() {
        when(workflow.getMyHistory(anyString(), any(), any())).thenReturn(history(List.of(report("EXP-A", 2))));
        when(cash.getMyApprovals(anyString(), any(), any())).thenReturn(new PageImpl<>(List.of(advance(3, "APPROVED"))));

        PageResponse<ApprovalRecordResponse> hits = service.page(APPROVER, true, "all", "exp-a", 0, 20);

        assertThat(hits.content()).extracting(ApprovalRecordResponse::reference).containsExactly("EXP-A");
    }

    @Test
    void emptyResultsGiveAnEmptyLastPage() {
        PageResponse<ApprovalRecordResponse> page = service.page(APPROVER, true, "all", null, 0, 20);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(page.last()).isTrue();
    }

    // ---------------------------------------------------------------- builders

    private static PageResponse<ExpenseReportResponse> history(List<ExpenseReportResponse> items) {
        return new PageResponse<>(items, 0, items.size(), items.size(), 1, true, true);
    }

    private static PageResponse<com.expense_management_service.dto.response.ApprovalQueueItemResponse> queue(
            List<com.expense_management_service.dto.response.ApprovalQueueItemResponse> items) {
        return new PageResponse<>(items, 0, items.size(), items.size(), 1, true, true);
    }

    private static ExpenseReportResponse report(String number, int dayOffset) {
        LocalDateTime when = BASE.plusDays(dayOffset);
        return new ExpenseReportResponse(UUID.randomUUID(), number, "5100014", "Title " + number, "purpose", "2026",
                UUID.randomUUID(), "Engineering", "APPROVED", "NONE", "NOT_APPLICABLE", UUID.randomUUID(), "INR", "INR",
                BigDecimal.TEN, BigDecimal.TEN, when, when, null, when, when, 1, false, false, 0, 0);
    }

    private static CashAdvanceResponse advance(int dayOffset, String status) {
        UUID id = new UUID(0L, dayOffset + 1L);
        LocalDateTime when = BASE.plusDays(dayOffset);
        return new CashAdvanceResponse(id, "5100014", APPROVER, BigDecimal.ONE, UUID.randomUUID(), "INR",
                BigDecimal.ONE, "purpose " + dayOffset, status, null, null, BigDecimal.ZERO, when, when);
    }

    private static String advanceRef(int dayOffset) {
        return ApprovalRecordsService.shortReference(new UUID(0L, dayOffset + 1L));
    }
}
