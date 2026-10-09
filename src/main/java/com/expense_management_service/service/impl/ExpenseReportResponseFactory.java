package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.mapper.ExpenseReportMapper;
import com.expense_management_service.repository.PolicyViolationRepository;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

/**
 * Shared read-model assembly for {@code ExpenseReportResponse}, extracted so both {@code
 * ApprovalWorkflowServiceImpl} and {@code FinanceVerificationServiceImpl} render a report's
 * editable/deletable/policy-warning fields identically rather than duplicating the same two
 * repository queries in each.
 */
@Component
@RequiredArgsConstructor
public class ExpenseReportResponseFactory {

    private final ExpenseReportMapper expenseReportMapper;
    private final PolicyViolationRepository policyViolationRepository;

    public ExpenseReportResponse toResponse(ExpenseReport report) {
        var violations = policyViolationRepository.findByLineItem_Report_ReportId(report.getReportId());
        int unjustified = (int) violations.stream().filter(v -> v.getJustification() == null).count();
        return expenseReportMapper.toResponse(report, report.getReportStatus().isEditable(), report.getReportStatus().isDeletable(),
                violations.size(), unjustified);
    }
}
