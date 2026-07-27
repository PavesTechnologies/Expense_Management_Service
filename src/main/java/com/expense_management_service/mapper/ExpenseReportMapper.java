package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.ExpenseReportRequest;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.ReportStatus;
import org.springframework.stereotype.Component;

@Component
public class ExpenseReportMapper {

    public ExpenseReport toEntity(ExpenseReportRequest request) {
        return ExpenseReport.builder()
                .reportNumber(request.reportNumber())
                .employeeId(request.employeeId())
                .title(request.title())
                .businessPurpose(request.businessPurpose())
                .reportStatus(toReportStatus(request.reportStatus()))
                .totalAmount(request.totalAmount())
                .reimbursableAmount(request.reimbursableAmount())
                .submittedAt(request.submittedAt())
                .approvedAt(request.approvedAt())
                .closedAt(request.closedAt())
                .build();
    }

    public void updateEntity(ExpenseReport entity, ExpenseReportRequest request) {
        entity.setReportNumber(request.reportNumber());
        entity.setEmployeeId(request.employeeId());
        entity.setTitle(request.title());
        entity.setBusinessPurpose(request.businessPurpose());
        entity.setReportStatus(toReportStatus(request.reportStatus()));
        entity.setTotalAmount(request.totalAmount());
        entity.setReimbursableAmount(request.reimbursableAmount());
        entity.setSubmittedAt(request.submittedAt());
        entity.setApprovedAt(request.approvedAt());
        entity.setClosedAt(request.closedAt());
    }

    public ExpenseReportResponse toResponse(ExpenseReport entity) {
        return new ExpenseReportResponse(
                entity.getReportId(),
                entity.getReportNumber(),
                entity.getEmployeeId(),
                entity.getTitle(),
                entity.getBusinessPurpose(),
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterId() : null,
                entity.getCostCenter() != null ? entity.getCostCenter().getCostCenterName() : null,
                entity.getReportStatus() != null ? entity.getReportStatus().name() : null,
                entity.getCurrency() != null ? entity.getCurrency().getCurrencyId() : null,
                entity.getCurrency() != null ? entity.getCurrency().getCurrencyCode() : null,
                entity.getTotalAmount(),
                entity.getReimbursableAmount(),
                entity.getSubmittedAt(),
                entity.getApprovedAt(),
                entity.getClosedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private ReportStatus toReportStatus(String reportStatus) {
        return reportStatus != null ? ReportStatus.valueOf(reportStatus) : null;
    }
}
