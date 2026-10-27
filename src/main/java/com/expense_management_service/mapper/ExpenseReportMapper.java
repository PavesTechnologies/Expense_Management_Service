package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.ExpenseReportRequest;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.PaymentRoutingStatus;
import com.expense_management_service.service.impl.SubmissionDeadlinePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class ExpenseReportMapper {

    /** totalAmount is the sum of line baseAmount, so it is labelled with the base currency. */
    @Value("${exchange.rate.base-currency:}")
    private String baseCurrencyCode;

    @Value("${expense.submission.deadline-days:30}")
    private int submissionDeadlineDays = SubmissionDeadlinePolicy.DEFAULT_DEADLINE_DAYS;

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
                .tripStartDate(request.tripStartDate())
                .tripEndDate(request.tripEndDate())
                .reportingPeriodStart(request.reportingPeriodStart())
                .reportingPeriodEnd(request.reportingPeriodEnd())
                .build();
    }

    /** Only the fields an owner may revise while a report stays editable — identity, number, fiscal period and workflow timestamps are immutable via this path. */
    public void updateEntity(ExpenseReport entity, ExpenseReportRequest request) {
        entity.setTitle(request.title());
        entity.setBusinessPurpose(request.businessPurpose());
        entity.setTripStartDate(request.tripStartDate());
        entity.setTripEndDate(request.tripEndDate());
        entity.setReportingPeriodStart(request.reportingPeriodStart());
        entity.setReportingPeriodEnd(request.reportingPeriodEnd());
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
                entity.getInvoiceHandoffStatus() != null ? entity.getInvoiceHandoffStatus().name() : null,
                entity.getCurrency() != null ? entity.getCurrency().getCurrencyId() : null,
                entity.getCurrency() != null ? entity.getCurrency().getCurrencyCode() : null,
                baseCurrencyCode,
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
                policyUnjustifiedCount,
                entity.getCashAdvance() != null ? entity.getCashAdvance().getAdvanceId() : null,
                entity.getTripStartDate(),
                entity.getTripEndDate(),
                displayedDeadline(entity),
                submissionDeadlineDays,
                entity.getReportingPeriodStart(),
                entity.getReportingPeriodEnd(),
                Boolean.TRUE.equals(entity.getNoPaymentDue()),
                toPaymentConfirmation(entity)
        );
    }

    /** Only once AP has confirmed the actual payment - never derived from approval or routing. */
    private static ExpenseReportResponse.PaymentConfirmation toPaymentConfirmation(ExpenseReport entity) {
        if (entity.getPaymentRoutingStatus() != PaymentRoutingStatus.PAYMENT_COMPLETED) {
            return null;
        }
        return new ExpenseReportResponse.PaymentConfirmation(
                entity.getPaymentCompletedAt(),
                entity.getPaymentCompletedBy(),
                entity.getPaymentReference(),
                entity.getPaymentMethod() != null ? entity.getPaymentMethod().name() : null,
                entity.getPaymentDate(),
                entity.getPaidAmount(),
                entity.getPaymentRemarks());
    }

    /**
     * The same deadline the submit check enforces: advance disbursement date for linked reports, trip end date
     * otherwise, replaced by a Finance extension. Null when no deadline applies (a reopen, or an advance not yet disbursed).
     */
    private LocalDate displayedDeadline(ExpenseReport entity) {
        CashAdvance advance = entity.getCashAdvance();
        boolean linked = advance != null;
        if (entity.isExpenseDeadlineReopened()) {
            return null;
        }
        LocalDate disbursedOn = linked && advance.getDisbursedAt() != null ? advance.getDisbursedAt().toLocalDate() : null;
        LocalDate extendedUntil = entity.getExpenseDeadlineExtendedUntil();
        return SubmissionDeadlinePolicy.effectiveDeadline(entity.getTripEndDate(), linked, disbursedOn, extendedUntil,
                submissionDeadlineDays);
    }
}
