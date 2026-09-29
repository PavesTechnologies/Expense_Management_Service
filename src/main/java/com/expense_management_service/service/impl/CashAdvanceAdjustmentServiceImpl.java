package com.expense_management_service.service.impl;

import java.math.BigDecimal;
import java.util.List;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.CashAdvanceAdjustmentRequest;
import com.expense_management_service.dto.response.CashAdvanceAdjustmentResponse;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceAdjustment;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.mapper.CashAdvanceAdjustmentMapper;
import com.expense_management_service.repository.CashAdvanceAdjustmentRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.CashAdvanceAdjustmentService;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.service.ApprovalEventPublisher;
import com.expense_management_service.security.RoleConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CashAdvanceAdjustmentServiceImpl implements CashAdvanceAdjustmentService {

    private final CashAdvanceAdjustmentRepository cashAdvanceAdjustmentRepository;
    private final CashAdvanceRepository cashAdvanceRepository;
    private final ExpenseReportRepository expenseReportRepository;
    private final CashAdvanceAdjustmentMapper cashAdvanceAdjustmentMapper;
    private final CurrentUserService currentUserService;
    private final AuditLogRepository auditLogRepository;
    private final ApprovalEventPublisher approvalEventPublisher;

    @Override
    public CashAdvanceAdjustmentResponse create(CashAdvanceAdjustmentRequest request) {
        if (!isFinanceCaller() && !isAdminCaller()) {
            throw new BusinessRuleViolationException("Only Finance Executives can process cash advance settlements");
        }
        CashAdvance advance = findCashAdvance(request.advanceId());

        List<String> allowedStatuses = List.of(
                "DISBURSED", "IN_PROGRESS", "RECONCILIATION_PENDING", "SUBMITTED_FOR_REVIEW",
                "UNDER_REVIEW", "APPROVED", "SETTLEMENT_PENDING", "EXPENSE_SUBMITTED",
                "EXPENSE_VERIFIED", "PARTIALLY_SETTLED", "PARTIALLY_ADJUSTED"
        );
        String advStatusUpper = (advance.getStatus() != null) ? advance.getStatus().toUpperCase() : "";
        if (!allowedStatuses.contains(advStatusUpper)) {
            throw new BusinessRuleViolationException("Cash advance must be in an active post-disbursement status to perform settlement adjustment. Current status: " + advance.getStatus());
        }

        ExpenseReport report = findReport(request.reportId());
        if (report.getReportStatus() != ReportStatus.APPROVED && report.getReportStatus() != ReportStatus.CLOSED) {
            throw new BusinessRuleViolationException("Expense report must be fully approved and finance-verified before adjusting against a cash advance. Current report status: " + report.getReportStatus());
        }

        if (report.getEmployeeId() != null && advance.getEmployeeId() != null
                && !report.getEmployeeId().trim().equalsIgnoreCase(advance.getEmployeeId().trim())
                && !isAdminCaller()) {
            throw new BusinessRuleViolationException("Expense report employee (" + report.getEmployeeId()
                    + ") does not match cash advance employee (" + advance.getEmployeeId() + ")");
        }

        List<CashAdvanceAdjustment> existingAdjustments = cashAdvanceAdjustmentRepository.findByCashAdvance_AdvanceId(advance.getAdvanceId());
        boolean duplicate = existingAdjustments.stream().anyMatch(adj -> adj.getReport() != null && adj.getReport().getReportId().equals(report.getReportId()));
        if (duplicate) {
            throw new BusinessRuleViolationException("Expense report " + report.getReportId() + " has already been adjusted against cash advance " + advance.getAdvanceId());
        }

        BigDecimal currentBalance = advance.getOutstandingBalance();
        if (currentBalance == null) {
            currentBalance = advance.getAmount();
        }

        BigDecimal adjustedAmount = request.adjustedAmount();
        if (adjustedAmount.compareTo(currentBalance) > 0) {
            throw new BusinessRuleViolationException("Adjusted amount (" + adjustedAmount + ") cannot exceed cash advance outstanding balance (" + currentBalance + ")");
        }

        BigDecimal newBalance = currentBalance.subtract(adjustedAmount);
        advance.setOutstandingBalance(newBalance);

        String previousStatus = advance.getStatus();
        if (newBalance.compareTo(BigDecimal.ZERO) == 0) {
            advance.setStatus("CLOSED");
        } else {
            advance.setStatus("SETTLEMENT_PENDING");
        }
        cashAdvanceRepository.save(advance);

        CashAdvanceAdjustment entity = cashAdvanceAdjustmentMapper.toEntity(request);
        entity.setCashAdvance(advance);
        entity.setReport(report);
        entity.setAdjustedAmount(adjustedAmount);
        entity.setAdjustedAt(LocalDateTime.now());
        String actingUser = currentUserService.getEmployeeId();
        if (entity.getAdjustedBy() == null || entity.getAdjustedBy().isBlank()) {
            entity.setAdjustedBy(actingUser);
        }

        CashAdvanceAdjustment saved = cashAdvanceAdjustmentRepository.save(entity);

        auditLogRepository.save(AuditLog.builder()
                .entityName("CashAdvanceAdjustment")
                .entityId(saved.getAdjustmentId())
                .action("ADJUSTMENT_CREATED")
                .oldValue("Balance: " + currentBalance + ", Status: " + previousStatus)
                .newValue("Adjusted: " + adjustedAmount + ", NewBalance: " + advance.getOutstandingBalance() + ", Status: " + advance.getStatus())
                .performedBy(actingUser)
                .performedAt(LocalDateTime.now())
                .build());

        approvalEventPublisher.publish("CASH_ADVANCE_ADJUSTED", advance.getAdvanceId(), "amount=" + adjustedAmount + ", reportId=" + report.getReportId());

        return cashAdvanceAdjustmentMapper.toResponse(saved);
    }

    @Override
    public CashAdvanceAdjustmentResponse update(UUID adjustmentId, CashAdvanceAdjustmentRequest request) {
        CashAdvanceAdjustment entity = findEntity(adjustmentId);
        cashAdvanceAdjustmentMapper.updateEntity(entity, request);
        entity.setCashAdvance(findCashAdvance(request.advanceId()));
        entity.setReport(findReport(request.reportId()));
        return cashAdvanceAdjustmentMapper.toResponse(cashAdvanceAdjustmentRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public CashAdvanceAdjustmentResponse getById(UUID adjustmentId) {
        return cashAdvanceAdjustmentMapper.toResponse(findEntity(adjustmentId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CashAdvanceAdjustmentResponse> getAll() {
        return cashAdvanceAdjustmentRepository.findAll().stream().map(cashAdvanceAdjustmentMapper::toResponse).toList();
    }

    @Override
    public void delete(UUID adjustmentId) {
        CashAdvanceAdjustment entity = findEntity(adjustmentId);
        CashAdvance advance = entity.getCashAdvance();
        String actingUser = currentUserService.getEmployeeId();
        if (advance != null && entity.getAdjustedAmount() != null) {
            BigDecimal currentBalance = advance.getOutstandingBalance() != null ? advance.getOutstandingBalance() : BigDecimal.ZERO;
            BigDecimal restoredBalance = currentBalance.add(entity.getAdjustedAmount());
            if (restoredBalance.compareTo(advance.getAmount()) > 0) {
                restoredBalance = advance.getAmount();
            }
            advance.setOutstandingBalance(restoredBalance);
            if (restoredBalance.compareTo(advance.getAmount()) == 0) {
                advance.setStatus("IN_PROGRESS");
            } else {
                advance.setStatus("SETTLEMENT_PENDING");
            }
            cashAdvanceRepository.save(advance);
        }
        cashAdvanceAdjustmentRepository.delete(entity);

        auditLogRepository.save(AuditLog.builder()
                .entityName("CashAdvanceAdjustment")
                .entityId(adjustmentId)
                .action("ADJUSTMENT_DELETED")
                .oldValue("Amount: " + entity.getAdjustedAmount())
                .newValue("DELETED")
                .performedBy(actingUser)
                .performedAt(LocalDateTime.now())
                .build());
    }

        private boolean isFinanceCaller() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getAuthorities() != null) {
                return auth.getAuthorities().stream()
                        .anyMatch(a -> RoleConstants.ROLE_FINANCE.equals(a.getAuthority())
                                || RoleConstants.FINANCE.equals(a.getAuthority())
                                || RoleConstants.ROLE_FINANCE_EXECUTIVE.equals(a.getAuthority())
                                || RoleConstants.FINANCE_EXECUTIVE.equals(a.getAuthority()));
            }
        } catch (Exception e) {
            log.debug("Could not determine user authorities from security context", e);
        }
        return false;
    }

private boolean isAdminCaller() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getAuthorities() != null) {
                return auth.getAuthorities().stream()
                        .anyMatch(a -> RoleConstants.ROLE_ADMIN.equals(a.getAuthority()) || RoleConstants.ADMIN.equals(a.getAuthority()));
            }
        } catch (Exception e) {
            log.debug("Could not determine user authorities from security context", e);
        }
        return false;
    }

    private CashAdvance findCashAdvance(UUID advanceId) {
        return cashAdvanceRepository.findById(advanceId)
                .orElseThrow(() -> new ResourceNotFoundException("CashAdvance not found with id: " + advanceId));
    }

    private ExpenseReport findReport(UUID reportId) {
        return expenseReportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseReport not found with id: " + reportId));
    }

    private CashAdvanceAdjustment findEntity(UUID adjustmentId) {
        return cashAdvanceAdjustmentRepository.findById(adjustmentId)
                .orElseThrow(() -> new ResourceNotFoundException("CashAdvanceAdjustment not found with id: " + adjustmentId));
    }
}


