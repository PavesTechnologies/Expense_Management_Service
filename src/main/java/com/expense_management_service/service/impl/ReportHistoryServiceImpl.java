package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.ReportHistoryEntryResponse;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.service.ExpenseReportService;
import com.expense_management_service.service.ReportHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportHistoryServiceImpl implements ReportHistoryService {

    private final ExpenseReportService expenseReportService;
    private final AuditLogRepository auditLogRepository;

    @Override
    public List<ReportHistoryEntryResponse> getHistory(UUID reportId) {
        // Authorizes with the report's own visibility rules (throws for anyone who can't view it).
        expenseReportService.getById(reportId);
        return auditLogRepository
                .findTop200ByEntityNameAndEntityIdOrderByPerformedAtDesc(ExpenseReportAuditListener.ENTITY_NAME, reportId).stream()
                .sorted(Comparator.comparing(AuditLog::getPerformedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(log -> new ReportHistoryEntryResponse(log.getAction(), log.getPerformedBy(), log.getPerformedAt(), log.getReason()))
                .toList();
    }
}
