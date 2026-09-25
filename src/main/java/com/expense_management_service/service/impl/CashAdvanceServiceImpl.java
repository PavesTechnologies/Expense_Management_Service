package com.expense_management_service.service.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.CashAdvanceRepaymentRequest;
import com.expense_management_service.dto.request.CashAdvanceRequest;
import com.expense_management_service.dto.response.CashAdvanceAdjustmentResponse;
import com.expense_management_service.dto.response.CashAdvanceRepaymentResponse;
import com.expense_management_service.dto.response.CashAdvanceResponse;
import com.expense_management_service.dto.response.CashAdvanceSettlementResponse;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceAdjustment;
import com.expense_management_service.entity.CashAdvanceRepayment;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.mapper.CashAdvanceAdjustmentMapper;
import com.expense_management_service.mapper.CashAdvanceMapper;
import com.expense_management_service.mapper.CashAdvanceRepaymentMapper;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.CashAdvanceAdjustmentRepository;
import com.expense_management_service.repository.CashAdvanceRepaymentRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.repository.CostCenterRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.RoleConstants;
import com.expense_management_service.service.ApprovalEventPublisher;
import com.expense_management_service.service.CashAdvanceService;
import com.expense_management_service.service.DelegationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CashAdvanceServiceImpl implements CashAdvanceService {

    private static final String MAX_ADVANCE_AMOUNT_CONFIG_KEY = "cash_advance.max_amount";

    private final CashAdvanceRepository cashAdvanceRepository;
    private final CurrencyRepository currencyRepository;
    private final SystemConfigurationRepository systemConfigurationRepository;
    private final EmployeeCacheRepository employeeCacheRepository;
    private final CostCenterRepository costCenterRepository;
    private final DelegationService delegationService;
    private final CashAdvanceMapper cashAdvanceMapper;
    private final CurrentUserService currentUserService;
    private final AuditLogRepository auditLogRepository;
    private final ApprovalEventPublisher approvalEventPublisher;
    private final CashAdvanceRepaymentRepository cashAdvanceRepaymentRepository;
    private final CashAdvanceRepaymentMapper cashAdvanceRepaymentMapper;
    private final CashAdvanceAdjustmentRepository cashAdvanceAdjustmentRepository;
    private final CashAdvanceAdjustmentMapper cashAdvanceAdjustmentMapper;

    @Override
    public CashAdvanceResponse create(CashAdvanceRequest request) {
        validateAndExtractFutureDate(request);
        CashAdvance entity = cashAdvanceMapper.toEntity(request);
        Currency currency = findCurrency(request.currencyId());
        entity.setCurrency(currency);

        if (entity.getEmployeeId() == null || entity.getEmployeeId().isBlank() || !isAdminCaller()) {
            entity.setEmployeeId(currentUserService.getEmployeeId());
        }

        resolveAndSetManager(entity);
        checkOverdueAdvances(entity.getEmployeeId());
        checkPolicyLimit(entity.getAmount());

        if (entity.getStatus() == null || entity.getStatus().isBlank() || "DRAFT".equalsIgnoreCase(entity.getStatus())) {
            entity.setStatus("DRAFT");
        } else if ("SUBMITTED".equalsIgnoreCase(entity.getStatus())) {
            entity.setStatus("SUBMITTED");
        }

        if (entity.getBaseAmount() == null) {
            entity.setBaseAmount(request.amount());
        }
        if (entity.getOutstandingBalance() == null) {
            entity.setOutstandingBalance(request.amount());
        }

        CashAdvance saved = cashAdvanceRepository.save(entity);
        String actingUser = currentUserService.getEmployeeId();

        auditLogRepository.save(AuditLog.builder()
                .entityName("CashAdvance")
                .entityId(saved.getAdvanceId())
                .action("CASH_ADVANCE_CREATED")
                .oldValue(null)
                .newValue(saved.getStatus())
                .performedBy(actingUser)
                .performedAt(LocalDateTime.now())
                .build());

        return cashAdvanceMapper.toResponse(saved);
    }

    @Override
    public CashAdvanceResponse update(UUID advanceId, CashAdvanceRequest request) {
        CashAdvance entity = findEntity(advanceId);
        String currentStatusUpper = entity.getStatus() != null ? entity.getStatus().toUpperCase() : "";
        String reqStatusUpper = request.status() != null ? request.status().toUpperCase().trim() : "";

        if (List.of("CLOSED", "SETTLED", "SETTLEMENT_PENDING", "PARTIALLY_SETTLED").contains(reqStatusUpper)) {
            if (!isFinanceCaller() && !isAdminCaller()) {
                throw new BusinessRuleViolationException("Only Finance Executives can process cash advance settlements");
            }
        }
        if (List.of("CLOSED", "SETTLED").contains(reqStatusUpper)) {
            BigDecimal targetBalance = request.outstandingBalance() != null ? request.outstandingBalance() : entity.getOutstandingBalance();
            if (targetBalance != null && targetBalance.compareTo(BigDecimal.ZERO) > 0) {
                throw new BusinessRuleViolationException("Cannot close cash advance when outstanding balance is greater than zero (" + targetBalance + ")");
            }
        }

        if (!List.of("CLOSED", "SETTLEMENT_PENDING", "SETTLED", "PARTIALLY_SETTLED").contains(reqStatusUpper)) {
            if ("APPROVED".equalsIgnoreCase(currentStatusUpper) || "DISBURSED".equalsIgnoreCase(currentStatusUpper) ||
                "IN_PROGRESS".equalsIgnoreCase(currentStatusUpper) || "SETTLED".equalsIgnoreCase(currentStatusUpper) ||
                "CLOSED".equalsIgnoreCase(currentStatusUpper) || "SUBMITTED".equalsIgnoreCase(currentStatusUpper) ||
                "PENDING_APPROVAL".equalsIgnoreCase(currentStatusUpper) || "PENDING_COST_CENTER_APPROVAL".equalsIgnoreCase(currentStatusUpper) ||
                "PENDING_FINANCE_APPROVAL".equalsIgnoreCase(currentStatusUpper)) {
                throw new BusinessRuleViolationException("Cannot update cash advance in status: " + entity.getStatus());
            }
            validateAndExtractFutureDate(request);
        }

        cashAdvanceMapper.updateEntity(entity, request);

        if (request.currencyId() != null) {
            Currency currency = findCurrency(request.currencyId());
            entity.setCurrency(currency);
        }

        resolveAndSetManager(entity);
        checkPolicyLimit(entity.getAmount());

        CashAdvance saved = cashAdvanceRepository.save(entity);
        return cashAdvanceMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public CashAdvanceResponse getById(UUID advanceId) {
        return cashAdvanceMapper.toResponse(findEntity(advanceId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CashAdvanceResponse> getAll() {
        return cashAdvanceRepository.findAll().stream().map(cashAdvanceMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CashAdvanceResponse> getMyAdvances(String employeeId, String status) {
        String empId = (employeeId != null && !employeeId.isBlank()) ? employeeId : currentUserService.getEmployeeId();
        if (status != null && !status.isBlank()) {
            return cashAdvanceRepository.findByEmployeeIdAndStatus(empId, status.toUpperCase())
                    .stream().map(cashAdvanceMapper::toResponse).toList();
        }
        return cashAdvanceRepository.findByEmployeeId(empId)
                .stream().map(cashAdvanceMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CashAdvanceResponse> getMyApprovals(String managerId, String status) {
        String mgrId = (managerId != null && !managerId.isBlank()) ? managerId : currentUserService.getEmployeeId();
        Set<String> approverIds = new HashSet<>(delegationService.resolveApproverIdsActingFor(mgrId));
        if (mgrId != null) {
            employeeCacheRepository.findByEmployeeId(mgrId).map(EmployeeCache::getEmployeeUuid).ifPresent(approverIds::add);
            employeeCacheRepository.findByEmployeeUuid(mgrId).map(EmployeeCache::getEmployeeId).ifPresent(approverIds::add);
        }

        String upperStatus = status != null ? status.toUpperCase().trim() : "";
        List<CashAdvance> allAdvances;

        if ("APPROVED".equals(upperStatus)) {
            allAdvances = cashAdvanceRepository.findByStatusIn(List.of("APPROVED", "DISBURSED", "IN_PROGRESS", "RECONCILIATION_PENDING", "SUBMITTED_FOR_REVIEW", "UNDER_REVIEW", "SETTLEMENT_PENDING", "CLOSED", "SETTLED"));
        } else if ("REJECTED".equals(upperStatus)) {
            allAdvances = cashAdvanceRepository.findByStatus("REJECTED");
        } else {
            List<String> targetStatuses = List.of("SUBMITTED", "PENDING_APPROVAL", "PENDING", "PENDING_MANAGER_APPROVAL", "PENDING_COST_CENTER_APPROVAL", "PENDING_COST_CENTER", "PENDING_FINANCE_APPROVAL", "PENDING_FINANCE");

            if (isFinanceCaller() || isAdminCaller()) {
                allAdvances = cashAdvanceRepository.findByStatusIn(targetStatuses);
            } else {
                List<CashAdvance> candidateAdvances = cashAdvanceRepository.findByStatusIn(targetStatuses);
                allAdvances = candidateAdvances.stream().filter(adv -> isApproverForAdvance(adv, approverIds)).toList();
            }
        }

        return allAdvances.stream().map(cashAdvanceMapper::toResponse).toList();
    }

    private boolean isApproverForAdvance(CashAdvance adv, Set<String> approverIds) {
        if (adv == null || approverIds == null || approverIds.isEmpty()) return false;
        String statusUpper = (adv.getStatus() != null) ? adv.getStatus().toUpperCase() : "";

        if (List.of("SUBMITTED", "PENDING", "PENDING_APPROVAL", "PENDING_MANAGER_APPROVAL").contains(statusUpper)) {
            if (adv.getManagerId() != null && approverIds.contains(adv.getManagerId())) {
                return true;
            }
            if (adv.getEmployeeId() != null) {
                String mgr = employeeCacheRepository.findByEmployeeId(adv.getEmployeeId())
                        .or(() -> employeeCacheRepository.findByEmployeeUuid(adv.getEmployeeId()))
                        .map(EmployeeCache::getManagerEmployeeId)
                        .orElse(null);
                if (mgr != null && approverIds.contains(mgr)) {
                    if (adv.getManagerId() == null || adv.getManagerId().isBlank()) {
                        adv.setManagerId(mgr);
                        cashAdvanceRepository.save(adv);
                    }
                    return true;
                }
            }
        } else if (List.of("PENDING_COST_CENTER_APPROVAL", "PENDING_COST_CENTER").contains(statusUpper)) {
            String ccOwner = resolveCostCenterOwner(adv);
            if (ccOwner != null && approverIds.contains(ccOwner)) {
                return true;
            }
            if (adv.getManagerId() != null && approverIds.contains(adv.getManagerId())) {
                return true;
            }
        } else if (List.of("PENDING_FINANCE_APPROVAL", "PENDING_FINANCE").contains(statusUpper)) {
            return isFinanceCaller() || isAdminCaller();
        }

        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CashAdvanceResponse> getFiltered(String employeeId, String status) {
        if (employeeId != null && !employeeId.isBlank()) {
            return getMyAdvances(employeeId, status);
        }
        if (status != null && !status.isBlank()) {
            return cashAdvanceRepository.findByStatus(status.toUpperCase())
                    .stream().map(cashAdvanceMapper::toResponse).toList();
        }
        return getAll();
    }

    @Override
    public CashAdvanceResponse submit(UUID advanceId) {
        CashAdvance entity = findEntity(advanceId);
        if ("SUBMITTED".equalsIgnoreCase(entity.getStatus()) || "PENDING_APPROVAL".equalsIgnoreCase(entity.getStatus()) ||
            "PENDING_COST_CENTER_APPROVAL".equalsIgnoreCase(entity.getStatus()) || "PENDING_FINANCE_APPROVAL".equalsIgnoreCase(entity.getStatus())) {
            throw new BusinessRuleViolationException("Cash advance is already submitted.");
        }
        if (!"DRAFT".equalsIgnoreCase(entity.getStatus()) && !"REJECTED".equalsIgnoreCase(entity.getStatus())) {
            throw new BusinessRuleViolationException("Only DRAFT or REJECTED cash advances can be submitted.");
        }
        if (entity.getSettlementDueDate() == null || !entity.getSettlementDueDate().isAfter(LocalDate.now())) {
            throw new BusinessRuleViolationException("Needed by date must be a future date.");
        }
        resolveAndSetManager(entity);
        checkOverdueAdvances(entity.getEmployeeId());
        checkPolicyLimit(entity.getAmount());
        String oldStatus = entity.getStatus();
        entity.setStatus("SUBMITTED");
        CashAdvance saved = cashAdvanceRepository.save(entity);

        String actingUser = currentUserService.getEmployeeId();
        auditLogRepository.save(AuditLog.builder()
                .entityName("CashAdvance")
                .entityId(saved.getAdvanceId())
                .action("CASH_ADVANCE_SUBMITTED")
                .oldValue(oldStatus)
                .newValue("SUBMITTED")
                .performedBy(actingUser)
                .performedAt(LocalDateTime.now())
                .build());

        approvalEventPublisher.publish("CASH_ADVANCE_SUBMITTED", saved.getAdvanceId(), "employeeId=" + saved.getEmployeeId() + ", managerId=" + saved.getManagerId());

        return cashAdvanceMapper.toResponse(saved);
    }

    @Override
    public CashAdvanceResponse approve(UUID advanceId, String approverId) {
        CashAdvance entity = findEntity(advanceId);
        String statusUpper = (entity.getStatus() != null) ? entity.getStatus().toUpperCase() : "";

        if ("APPROVED".equalsIgnoreCase(statusUpper)) {
            throw new BusinessRuleViolationException("Cash advance is already approved.");
        }
        if ("DISBURSED".equalsIgnoreCase(statusUpper)) {
            throw new BusinessRuleViolationException("Cash advance is already disbursed.");
        }

        List<String> pendingStatuses = List.of("SUBMITTED", "PENDING_APPROVAL", "PENDING", "PENDING_MANAGER_APPROVAL", "PENDING_COST_CENTER_APPROVAL", "PENDING_COST_CENTER", "PENDING_FINANCE_APPROVAL", "PENDING_FINANCE");
        if (!pendingStatuses.contains(statusUpper)) {
            throw new BusinessRuleViolationException("Only pending cash advances can be approved.");
        }

        validateApproverAuthorization(entity, approverId);
        String oldStatus = entity.getStatus();
        String actingUser = (approverId != null && !approverId.isBlank()) ? approverId : currentUserService.getEmployeeId();
        String nextStatus;

        if (List.of("SUBMITTED", "PENDING_APPROVAL", "PENDING", "PENDING_MANAGER_APPROVAL").contains(statusUpper)) {
            String ccOwner = resolveCostCenterOwner(entity);
            if (ccOwner != null && !ccOwner.isBlank() && !ccOwner.equalsIgnoreCase(actingUser) && !ccOwner.equalsIgnoreCase(entity.getEmployeeId()) && !ccOwner.equalsIgnoreCase(entity.getManagerId())) {
                nextStatus = "PENDING_COST_CENTER_APPROVAL";
            } else {
                nextStatus = "PENDING_FINANCE_APPROVAL";
            }
        } else if (List.of("PENDING_COST_CENTER_APPROVAL", "PENDING_COST_CENTER").contains(statusUpper)) {
            nextStatus = "PENDING_FINANCE_APPROVAL";
        } else {
            nextStatus = "APPROVED";
        }

        entity.setStatus(nextStatus);
        CashAdvance saved = cashAdvanceRepository.save(entity);

        auditLogRepository.save(AuditLog.builder()
                .entityName("CashAdvance")
                .entityId(saved.getAdvanceId())
                .action("CASH_ADVANCE_APPROVED")
                .oldValue(oldStatus)
                .newValue(nextStatus)
                .performedBy(actingUser)
                .performedAt(LocalDateTime.now())
                .build());

        approvalEventPublisher.publish("CASH_ADVANCE_APPROVED", saved.getAdvanceId(), "approvedBy=" + actingUser + ", nextStatus=" + nextStatus);

        return cashAdvanceMapper.toResponse(saved);
    }

    @Override
    public CashAdvanceResponse reject(UUID advanceId, String approverId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessRuleViolationException("Rejection reason is mandatory when rejecting a cash advance");
        }
        CashAdvance entity = findEntity(advanceId);
        String statusUpper = (entity.getStatus() != null) ? entity.getStatus().toUpperCase() : "";

        if ("REJECTED".equalsIgnoreCase(statusUpper)) {
            throw new BusinessRuleViolationException("Cash advance is already rejected.");
        }
        if ("DISBURSED".equalsIgnoreCase(statusUpper)) {
            throw new BusinessRuleViolationException("Cash advance is already disbursed.");
        }

        List<String> pendingStatuses = List.of("SUBMITTED", "PENDING_APPROVAL", "PENDING", "PENDING_MANAGER_APPROVAL", "PENDING_COST_CENTER_APPROVAL", "PENDING_COST_CENTER", "PENDING_FINANCE_APPROVAL", "PENDING_FINANCE");
        if (!pendingStatuses.contains(statusUpper)) {
            throw new BusinessRuleViolationException("Only pending cash advances can be rejected.");
        }

        validateApproverAuthorization(entity, approverId);
        String oldStatus = entity.getStatus();
        entity.setStatus("REJECTED");
        CashAdvance saved = cashAdvanceRepository.save(entity);

        String actingUser = (approverId != null && !approverId.isBlank()) ? approverId : currentUserService.getEmployeeId();
        auditLogRepository.save(AuditLog.builder()
                .entityName("CashAdvance")
                .entityId(saved.getAdvanceId())
                .action("CASH_ADVANCE_REJECTED")
                .oldValue(oldStatus)
                .newValue("REJECTED: " + reason)
                .performedBy(actingUser)
                .performedAt(LocalDateTime.now())
                .build());

        approvalEventPublisher.publish("CASH_ADVANCE_REJECTED", saved.getAdvanceId(), "rejectedBy=" + actingUser + ", reason=" + reason);

        return cashAdvanceMapper.toResponse(saved);
    }

    @Override
    public CashAdvanceResponse disburse(UUID advanceId, String financeUserId) {
        CashAdvance entity = findEntity(advanceId);
        String currentStatusUpper = (entity.getStatus() != null) ? entity.getStatus().toUpperCase() : "";
        if ("DISBURSED".equalsIgnoreCase(currentStatusUpper) || "IN_PROGRESS".equalsIgnoreCase(currentStatusUpper)) {
            throw new BusinessRuleViolationException("Cash advance is already disbursed.");
        }
        if (!"APPROVED".equalsIgnoreCase(currentStatusUpper)) {
            throw new BusinessRuleViolationException("Only APPROVED cash advances can be disbursed.");
        }
        String oldStatus = entity.getStatus();
        entity.setStatus("IN_PROGRESS");
        if (entity.getOutstandingBalance() == null) {
            entity.setOutstandingBalance(entity.getAmount());
        }
        CashAdvance saved = cashAdvanceRepository.save(entity);

        String actingUser = (financeUserId != null && !financeUserId.isBlank()) ? financeUserId : currentUserService.getEmployeeId();
        auditLogRepository.save(AuditLog.builder()
                .entityName("CashAdvance")
                .entityId(saved.getAdvanceId())
                .action("CASH_ADVANCE_DISBURSED")
                .oldValue(oldStatus)
                .newValue("IN_PROGRESS")
                .performedBy(actingUser)
                .performedAt(LocalDateTime.now())
                .build());

        approvalEventPublisher.publish("CASH_ADVANCE_DISBURSED", saved.getAdvanceId(), "disbursedBy=" + actingUser);

        return cashAdvanceMapper.toResponse(saved);
    }

    @Override
    public CashAdvanceRepaymentResponse repay(CashAdvanceRepaymentRequest request) {
        if (!isFinanceCaller() && !isAdminCaller()) {
            throw new BusinessRuleViolationException("Only Finance Executives can process cash advance settlements");
        }
        CashAdvance advance = findEntity(request.advanceId());
        String currentStatusUpper = advance.getStatus() != null ? advance.getStatus().toUpperCase() : "";
        List<String> allowedRepayStatuses = List.of(
                "DISBURSED", "IN_PROGRESS", "RECONCILIATION_PENDING", "SUBMITTED_FOR_REVIEW",
                "UNDER_REVIEW", "APPROVED", "SETTLEMENT_PENDING", "EXPENSE_SUBMITTED",
                "EXPENSE_VERIFIED", "ADJUSTED", "PARTIALLY_SETTLED", "PARTIALLY_ADJUSTED", "OVERDUE"
        );
        if (!allowedRepayStatuses.contains(currentStatusUpper)) {
            throw new BusinessRuleViolationException("Cannot record repayment for cash advance in status: " + advance.getStatus());
        }

        BigDecimal currentBalance = advance.getOutstandingBalance();
        if (currentBalance == null) {
            currentBalance = advance.getAmount();
        }
        if (currentBalance.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessRuleViolationException("Cash advance outstanding balance is already zero.");
        }
        if (request.amount().compareTo(currentBalance) > 0) {
            throw new BusinessRuleViolationException("Repayment amount (" + request.amount() + ") cannot exceed outstanding balance (" + currentBalance + ")");
        }

        BigDecimal newBalance = currentBalance.subtract(request.amount());
        advance.setOutstandingBalance(newBalance);
        String previousStatus = advance.getStatus();
        if (newBalance.compareTo(BigDecimal.ZERO) == 0) {
            advance.setStatus("CLOSED");
        } else {
            advance.setStatus("SETTLEMENT_PENDING");
        }
        cashAdvanceRepository.save(advance);

        CashAdvanceRepayment repayment = cashAdvanceRepaymentMapper.toEntity(request);
        repayment.setCashAdvance(advance);
        String actingUser = currentUserService.getEmployeeId();
        repayment.setRepaidBy(actingUser);
        repayment.setRepaidAt(LocalDateTime.now());

        CashAdvanceRepayment saved = cashAdvanceRepaymentRepository.save(repayment);

        auditLogRepository.save(AuditLog.builder()
                .entityName("CashAdvanceRepayment")
                .entityId(saved.getRepaymentId())
                .action("REPAYMENT_RECORDED")
                .oldValue("Balance: " + currentBalance + ", Status: " + previousStatus)
                .newValue("Repaid: " + request.amount() + ", NewBalance: " + newBalance + ", Status: " + advance.getStatus())
                .performedBy(actingUser)
                .performedAt(LocalDateTime.now())
                .build());

        approvalEventPublisher.publish("CASH_ADVANCE_REPAID", advance.getAdvanceId(), "amount=" + request.amount());
        if ("CLOSED".equalsIgnoreCase(advance.getStatus()) || "SETTLED".equalsIgnoreCase(advance.getStatus())) {
            approvalEventPublisher.publish("CASH_ADVANCE_SETTLED", advance.getAdvanceId(), "finalBalance=0.0000");
        }

        return cashAdvanceRepaymentMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public CashAdvanceSettlementResponse getSettlement(UUID advanceId) {
        CashAdvance advance = findEntity(advanceId);
        List<CashAdvanceAdjustment> adjustments = cashAdvanceAdjustmentRepository.findByCashAdvance_AdvanceId(advanceId);
        List<CashAdvanceRepayment> repayments = cashAdvanceRepaymentRepository.findByCashAdvance_AdvanceId(advanceId);

        BigDecimal totalAdjusted = adjustments.stream()
                .map(CashAdvanceAdjustment::getAdjustedAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalRepaid = repayments.stream()
                .map(CashAdvanceRepayment::getAmount)
                .filter(r -> r != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal calculatedBalance = advance.getAmount().subtract(totalAdjusted).subtract(totalRepaid);
        BigDecimal repaymentAmount = calculatedBalance.compareTo(BigDecimal.ZERO) > 0 ? calculatedBalance : BigDecimal.ZERO;
        BigDecimal reimbursementAmount = calculatedBalance.compareTo(BigDecimal.ZERO) < 0 ? calculatedBalance.abs() : BigDecimal.ZERO;
        String settlementStatus;
        if (calculatedBalance.compareTo(BigDecimal.ZERO) > 0) {
            settlementStatus = "REPAYMENT_DUE";
        } else if (calculatedBalance.compareTo(BigDecimal.ZERO) < 0) {
            settlementStatus = "REIMBURSEMENT_DUE";
        } else {
            settlementStatus = "FULLY_SETTLED";
        }

        List<CashAdvanceAdjustmentResponse> adjResponses = adjustments.stream().map(cashAdvanceAdjustmentMapper::toResponse).toList();
        List<CashAdvanceRepaymentResponse> repResponses = repayments.stream().map(cashAdvanceRepaymentMapper::toResponse).toList();

        return new CashAdvanceSettlementResponse(
                advance.getAdvanceId(),
                advance.getEmployeeId(),
                advance.getAmount(),
                advance.getCurrency() != null ? advance.getCurrency().getCurrencyCode() : null,
                totalAdjusted,
                totalRepaid,
                calculatedBalance,
                advance.getStatus(),
                advance.getSettlementDueDate(),
                repaymentAmount,
                reimbursementAmount,
                settlementStatus,
                adjResponses,
                repResponses
        );
    }

    @Override
    public void processOverdueAdvances() {
        List<CashAdvance> activeAdvances = cashAdvanceRepository.findByStatusIn(List.of(
                "DISBURSED", "IN_PROGRESS", "RECONCILIATION_PENDING", "SUBMITTED_FOR_REVIEW",
                "UNDER_REVIEW", "APPROVED", "SETTLEMENT_PENDING", "PARTIALLY_SETTLED", "PARTIALLY_ADJUSTED"
        ));
        LocalDate today = LocalDate.now();
        for (CashAdvance advance : activeAdvances) {
            if (advance.getSettlementDueDate() != null && advance.getSettlementDueDate().isBefore(today)) {
                if (advance.getOutstandingBalance() != null && advance.getOutstandingBalance().compareTo(BigDecimal.ZERO) > 0) {
                    String oldStatus = advance.getStatus();
                    advance.setStatus("OVERDUE");
                    cashAdvanceRepository.save(advance);

                    auditLogRepository.save(AuditLog.builder()
                            .entityName("CashAdvance")
                            .entityId(advance.getAdvanceId())
                            .action("OVERDUE_MARKED")
                            .oldValue(oldStatus)
                            .newValue("OVERDUE")
                            .performedBy("SYSTEM")
                            .performedAt(LocalDateTime.now())
                            .build());

                    approvalEventPublisher.publish("CASH_ADVANCE_OVERDUE", advance.getAdvanceId(), "due=" + advance.getSettlementDueDate());
                }
            }
        }
    }

    @Override
    public CashAdvanceResponse cancel(UUID advanceId) {
        CashAdvance entity = findEntity(advanceId);
        String st = (entity.getStatus() != null) ? entity.getStatus().toUpperCase() : "";
        if (List.of("DISBURSED", "IN_PROGRESS", "CLOSED", "SETTLED", "PARTIALLY_SETTLED").contains(st)) {
            throw new BusinessRuleViolationException("Cannot cancel a cash advance that is already disbursed or settled.");
        }
        entity.setStatus("CANCELLED");
        return cashAdvanceMapper.toResponse(cashAdvanceRepository.save(entity));
    }

    @Override
    public void delete(UUID advanceId) {
        cashAdvanceRepository.delete(findEntity(advanceId));
    }

    private LocalDate validateAndExtractFutureDate(CashAdvanceRequest request) {
        LocalDate dueDate = request.neededByDate() != null ? request.neededByDate() : request.settlementDueDate();
        if (dueDate == null) {
            throw new BusinessRuleViolationException("Needed by date is required and must be in the future.");
        }
        if (!dueDate.isAfter(LocalDate.now())) {
            throw new BusinessRuleViolationException("Needed by date must be a future date.");
        }
        return dueDate;
    }

    private void resolveAndSetManager(CashAdvance entity) {
        if (entity.getEmployeeId() != null && !entity.getEmployeeId().isBlank()) {
            employeeCacheRepository.findByEmployeeId(entity.getEmployeeId())
                    .or(() -> employeeCacheRepository.findByEmployeeUuid(entity.getEmployeeId()))
                    .map(EmployeeCache::getManagerEmployeeId)
                    .filter(mgrId -> mgrId != null && !mgrId.isBlank())
                    .ifPresent(entity::setManagerId);
        }
    }

    private String resolveCostCenterOwner(CashAdvance advance) {
        if (advance == null || advance.getEmployeeId() == null) {
            return null;
        }
        String empId = advance.getEmployeeId();
        Optional<EmployeeCache> empOpt = employeeCacheRepository.findByEmployeeId(empId)
                .or(() -> employeeCacheRepository.findByEmployeeUuid(empId));
        if (empOpt.isPresent() && empOpt.get().getDepartmentUuid() != null) {
            String deptUuidStr = empOpt.get().getDepartmentUuid();
            try {
                UUID deptUuid = UUID.fromString(deptUuidStr);
                List<CostCenter> costCenters = costCenterRepository.findAll();
                for (CostCenter cc : costCenters) {
                    if (deptUuid.equals(cc.getDepartmentUuid()) && cc.getOwnerEmployeeId() != null && !cc.getOwnerEmployeeId().isBlank()) {
                        return cc.getOwnerEmployeeId();
                    }
                }
            } catch (Exception e) {
                log.debug("Invalid departmentUuid for employee {}: {}", empId, deptUuidStr);
            }
        }
        return null;
    }

    private void validateApproverAuthorization(CashAdvance entity, String approverId) {
        String actingUser = (approverId != null && !approverId.isBlank()) ? approverId : currentUserService.getEmployeeId();

        if (actingUser != null && entity.getEmployeeId() != null 
                && actingUser.trim().equalsIgnoreCase(entity.getEmployeeId().trim())) {
            throw new BusinessRuleViolationException("Employee cannot approve or reject their own cash advance request");
        }

        if (isAdminCaller()) {
            return;
        }

        String statusUpper = (entity.getStatus() != null) ? entity.getStatus().toUpperCase() : "";

        if (List.of("PENDING_FINANCE_APPROVAL", "PENDING_FINANCE").contains(statusUpper)) {
            if (isFinanceCaller() || (approverId != null && (approverId.toUpperCase().contains("FINANCE") || approverId.toUpperCase().contains("ADMIN")))) {
                return;
            }
            throw new BusinessRuleViolationException("Only Finance Executives can perform final approval or rejection at this stage");
        }

        if (List.of("PENDING_COST_CENTER_APPROVAL", "PENDING_COST_CENTER").contains(statusUpper)) {
            String ccOwner = resolveCostCenterOwner(entity);
            Set<String> actingUserIds = new HashSet<>();
            if (actingUser != null) {
                actingUserIds.add(actingUser);
                employeeCacheRepository.findByEmployeeId(actingUser).map(EmployeeCache::getEmployeeUuid).ifPresent(actingUserIds::add);
                employeeCacheRepository.findByEmployeeUuid(actingUser).map(EmployeeCache::getEmployeeId).ifPresent(actingUserIds::add);
            }
            if (ccOwner != null && !ccOwner.isBlank()) {
                for (String uid : actingUserIds) {
                    if (uid.equalsIgnoreCase(ccOwner) || delegationService.canAct(uid, ccOwner)) {
                        return;
                    }
                }
            }
            if (isFinanceCaller()) {
                return;
            }
            String assignedManager = entity.getManagerId();
            if (assignedManager != null && !assignedManager.isBlank()) {
                for (String uid : actingUserIds) {
                    if (delegationService.canAct(uid, assignedManager)) {
                        return;
                    }
                }
            }
            throw new BusinessRuleViolationException("Only the Cost Center Owner (or active delegate/Finance) can approve or reject this cash advance");
        }

        String assignedManager = entity.getManagerId();
        if (assignedManager == null || assignedManager.isBlank()) {
            assignedManager = employeeCacheRepository.findByEmployeeId(entity.getEmployeeId())
                    .or(() -> employeeCacheRepository.findByEmployeeUuid(entity.getEmployeeId()))
                    .map(EmployeeCache::getManagerEmployeeId)
                    .orElse(null);
            if (assignedManager != null && !assignedManager.isBlank()) {
                entity.setManagerId(assignedManager);
                cashAdvanceRepository.save(entity);
            }
        }

        Set<String> actingUserIds = new HashSet<>();
        if (actingUser != null) {
            actingUserIds.add(actingUser);
            employeeCacheRepository.findByEmployeeId(actingUser).map(EmployeeCache::getEmployeeUuid).ifPresent(actingUserIds::add);
            employeeCacheRepository.findByEmployeeUuid(actingUser).map(EmployeeCache::getEmployeeId).ifPresent(actingUserIds::add);
        }

        if (assignedManager != null && !assignedManager.isBlank()) {
            for (String uid : actingUserIds) {
                if (delegationService.canAct(uid, assignedManager)) {
                    return;
                }
            }
        }
        if (isFinanceCaller()) {
            return;
        }

        throw new BusinessRuleViolationException("Only the authorized manager (or active delegate) can approve or reject this cash advance");
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

    private void checkOverdueAdvances(String employeeId) {
        if (employeeId == null || employeeId.isBlank()) {
            return;
        }
        List<CashAdvance> activeAdvances = cashAdvanceRepository.findByEmployeeIdAndStatusIn(
                employeeId, List.of("DISBURSED", "IN_PROGRESS", "RECONCILIATION_PENDING", "SUBMITTED_FOR_REVIEW", "UNDER_REVIEW", "SETTLEMENT_PENDING", "PARTIALLY_SETTLED", "PARTIALLY_ADJUSTED", "OVERDUE"));
        for (CashAdvance advance : activeAdvances) {
            if ("OVERDUE".equalsIgnoreCase(advance.getStatus()) 
                    && advance.getOutstandingBalance() != null && advance.getOutstandingBalance().compareTo(BigDecimal.ZERO) > 0) {
                throw new BusinessRuleViolationException("Cannot request or submit a new cash advance when you have overdue unsettled advances (Advance ID: " + advance.getAdvanceId() + ")");
            }
            if (advance.getSettlementDueDate() != null && advance.getSettlementDueDate().isBefore(LocalDate.now())) {
                if (advance.getOutstandingBalance() != null && advance.getOutstandingBalance().compareTo(BigDecimal.ZERO) > 0) {
                    throw new BusinessRuleViolationException("Cannot request or submit a new cash advance when you have overdue unsettled advances (Advance ID: " + advance.getAdvanceId() + ")");
                }
            }
        }
    }

    private void checkPolicyLimit(BigDecimal amount) {
        if (amount == null || systemConfigurationRepository == null) {
            return;
        }
        systemConfigurationRepository.findByConfigKey(MAX_ADVANCE_AMOUNT_CONFIG_KEY)
                .ifPresent(config -> {
                    try {
                        BigDecimal maxAmount = new BigDecimal(config.getConfigValue().trim());
                        if (amount.compareTo(maxAmount) > 0) {
                            throw new BusinessRuleViolationException("Cash advance amount (" + amount + ") exceeds maximum policy limit of " + maxAmount);
                        }
                    } catch (NumberFormatException e) {
                        log.warn("Invalid decimal format for config key {}: {}", MAX_ADVANCE_AMOUNT_CONFIG_KEY, config.getConfigValue());
                    }
                });
    }

    private Currency findCurrency(UUID currencyId) {
        return currencyRepository.findById(currencyId)
                .orElseThrow(() -> new ResourceNotFoundException("Currency not found with id: " + currencyId));
    }

    private CashAdvance findEntity(UUID advanceId) {
        return cashAdvanceRepository.findById(advanceId)
                .orElseThrow(() -> new ResourceNotFoundException("CashAdvance not found with id: " + advanceId));
    }
}
