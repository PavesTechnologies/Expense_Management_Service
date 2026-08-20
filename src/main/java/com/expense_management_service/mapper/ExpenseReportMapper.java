package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.ExpenseReportRequest;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.entity.ExpenseReport;
import org.springframework.stereotype.Component;

@Component
public class ExpenseReportMapper {

    /**
     * Only title/businessPurpose come from the request - employeeId, fiscalYear, reportNumber,
     * reportStatus, costCenter, currency, and the monetary/workflow fields are server-derived
     * and set by {@code ExpenseReportServiceImpl} after this call, never by the client
     * (see {@code ExpenseReportRequest}'s Javadoc on why those fields are absent from it).
     */
    public ExpenseReport toEntity(ExpenseReportRequest request) {
        return ExpenseReport.builder()
                .title(request.title())
                .businessPurpose(request.businessPurpose())
                .build();
    }

    /** Only the fields an owner may revise while a report stays editable — identity, number, fiscal period and workflow timestamps are immutable via this path. */
    public void updateEntity(ExpenseReport entity, ExpenseReportRequest request) {
        entity.setTitle(request.title());
        entity.setBusinessPurpose(request.businessPurpose());
    }

    /** Convenience overload for call sites that don't (yet) have policy warning counts on hand — defaults to zero. */
    public ExpenseReportResponse toResponse(ExpenseReport entity, boolean editable, boolean deletable) {
        return toResponse(entity, editable, deletable, 0, 0);
    }

    public ExpenseReportResponse toResponse(ExpenseReport entity, boolean editable, boolean deletable,
                                             int policyWarningCount, int policyUnjustifiedCount) {
        return new ExpenseReportResponse(
                entity.getReportId(),
                entity.getReportNumber(),
                entity.getEmployeeId(),
                entity.getTitle(),
                entity.getBusinessPurpose(),
                entity.getFiscalYear(),
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterId() : null,
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterName() : null,
                entity.getReportStatus() != null ? entity.getReportStatus().name() : null,
                entity.getPaymentRoutingStatus() != null ? entity.getPaymentRoutingStatus().name() : null,
                entity.getCurrency() != null ? entity.getCurrency().getCurrencyId() : null,
                entity.getCurrency() != null ? entity.getCurrency().getCurrencyCode() : null,
                entity.getTotalAmount(),
                entity.getReimbursableAmount(),
                entity.getSubmittedAt(),
                entity.getApprovedAt(),
                entity.getClosedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getExpenseLineItems() == null ? 0 : entity.getExpenseLineItems().size(),
                editable,
                deletable,
                policyWarningCount,
                policyUnjustifiedCount
        );
    }
}
