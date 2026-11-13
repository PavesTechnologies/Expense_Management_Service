package com.expense_management_service.service.impl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.CashAdvanceDisbursement;
import com.expense_management_service.dto.request.CashAdvanceRepaymentRequest;
import com.expense_management_service.dto.request.CashAdvanceRequest;
import com.expense_management_service.dto.response.CashAdvanceAdjustmentResponse;
import com.expense_management_service.dto.response.CashAdvanceRepaymentResponse;
import com.expense_management_service.dto.response.CashAdvanceResponse;
import com.expense_management_service.dto.response.CashAdvanceSettlementResponse;
import com.expense_management_service.dto.response.StoredProof;
import com.expense_management_service.dto.response.CashAdvanceDisbursementSummary;
import com.expense_management_service.dto.response.CashAdvanceUtilizationResponse;
import com.expense_management_service.entity.AuditLog;
import com.expense_management_service.storage.StorageService;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.dto.request.CashAdvancePreviewRequest;
import com.expense_management_service.dto.response.CashAdvancePreviewResponse;
import com.expense_management_service.dto.response.EmployeeProfileResponse;
import com.expense_management_service.enums.CashAdvanceRequestType;
import com.expense_management_service.entity.CashAdvanceAdjustment;
import com.expense_management_service.entity.CashAdvanceRepayment;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.CostCenterBudget;
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
import com.expense_management_service.repository.CostCenterBudgetRepository;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CashAdvanceServiceImpl implements CashAdvanceService {

    /** The business clock: real time in the business zone, or the development override. Tests replace it. */
    @Autowired
    private Clock clock = Clock.systemDefaultZone();

    private final CashAdvanceBudgetMovements cashAdvanceBudgetMovements;
    private final CashAdvanceBalanceCalculator balanceCalculator;
    private final CashAdvanceRecoveryService cashAdvanceRecoveryService;

    /** The disbursement mode recorded when carried-in balance covers the whole advance and no cash is paid. */
    private static final String CARRIED_FORWARD_MODE = "CARRIED_FORWARD";
    private static final String MAX_ADVANCE_AMOUNT_CONFIG_KEY =
            "cash_advance.max_amount";

    private final CashAdvanceRepository cashAdvanceRepository;
    private final CurrencyRepository currencyRepository;
    private final SystemConfigurationRepository systemConfigurationRepository;
    private final EmployeeCacheRepository employeeCacheRepository;
    private final CostCenterRepository costCenterRepository;
    private final CostCenterBudgetRepository costCenterBudgetRepository;
    private final DelegationService delegationService;
    private final CashAdvanceMapper cashAdvanceMapper;
    private final CurrentUserService currentUserService;
    private final CashAdvanceCarryForwardService carryForwardService;
    private final AuditLogRepository auditLogRepository;
    private final ApprovalEventPublisher approvalEventPublisher;
    private final CashAdvanceBudgetAllocator cashAdvanceBudgetAllocator;
    private final CashAdvanceRepaymentRepository cashAdvanceRepaymentRepository;
    private final CashAdvanceRepaymentMapper cashAdvanceRepaymentMapper;
    private final CashAdvanceAdjustmentRepository cashAdvanceAdjustmentRepository;
    private final CashAdvanceAdjustmentMapper cashAdvanceAdjustmentMapper;
    private final CashAdvanceUtilizationCalculator cashAdvanceUtilizationCalculator;
    private final StorageService storageService;
    private final CashAdvanceBalanceLedger cashAdvanceBalanceLedger;
    private final CashAdvanceEmailService cashAdvanceEmailService;


    // ============================================================
    // CREATE
    // ============================================================

    @Override
    public CashAdvanceResponse create(CashAdvanceRequest request) {

        validateAndExtractFutureDate(request);
        validateRoundAmount(request.amount());

        CashAdvance entity = cashAdvanceMapper.toEntity(request);
        entity.setRequestedAmount(request.amount());

        Currency currency = findCurrency(request.currencyId());
        entity.setCurrency(currency);

        // Persist the Cost Center selected by the frontend.
        // The request contains costCenterId; the entity stores the actual CostCenter relationship.
        if (request.costCenterId() != null) {
            CostCenter costCenter = costCenterRepository.findById(request.costCenterId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Cost Center not found with id: " + request.costCenterId()
                    ));
            entity.setCostCenter(costCenter);
        }

        if (entity.getEmployeeId() == null
                || entity.getEmployeeId().isBlank()
                || !isAdminCaller()) {

            entity.setEmployeeId(
                    currentUserService.getEmployeeId()
            );
        }

        applyAllocationRules(entity, request);
        resolveAndSetManager(entity);


        // Validate cash advance amount against configured limit
        checkPolicyLimit(entity.getAmount());

        if (entity.getStatus() == null
                || entity.getStatus().isBlank()
                || "DRAFT".equalsIgnoreCase(entity.getStatus())) {

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

        auditLogRepository.save(
                AuditLog.builder()
                        .entityName("CashAdvance")
                        .entityId(saved.getAdvanceId())
                        .action("CASH_ADVANCE_CREATED")
                        .oldValue(null)
                        .newValue(saved.getStatus())
                        .performedBy(actingUser)
                        .performedAt(LocalDateTime.now())
                        .build()
        );

        return cashAdvanceMapper.toResponse(saved);
    }


    // ============================================================
    // UPDATE
    // ============================================================

    @Override
    public CashAdvanceResponse update(
            UUID advanceId,
            CashAdvanceRequest request) {

        CashAdvance entity = findEntity(advanceId);

        String currentStatusUpper =
                entity.getStatus() != null
                        ? entity.getStatus().toUpperCase()
                        : "";

        // Status and outstandingBalance cannot be updated via request - they're managed server-side
        // Validate that entity is in DRAFT or SUBMITTED status before allowing updates
        // Only a draft can be edited. A submitted request is read-only, and a manager's query will reopen it for the
        // employee's response (a separate, authorised workflow).
        if (!"DRAFT".equals(currentStatusUpper)) {
            throw new BusinessRuleViolationException(
                    "Only a draft can be edited. A submitted request is read-only. Current status: " + entity.getStatus()
            );
        }

        validateAndExtractFutureDate(request);

        cashAdvanceMapper.updateEntity(entity, request);
        applyAllocationRules(entity, request);
        // Nothing has been paid out yet, so the balance is still the full requested amount. Keep it in step with the edit.
        if (entity.getDisbursedAt() == null) {
            entity.setOutstandingBalance(entity.getAmount());
        }

        if (request.currencyId() != null) {

            Currency currency =
                    findCurrency(request.currencyId());

            entity.setCurrency(currency);
        }

        // Keep the existing Cost Center when an update does not supply one.
        // If a Cost Center ID is supplied, replace the relationship with that Cost Center.
        if (request.costCenterId() != null) {
            CostCenter costCenter = costCenterRepository.findById(request.costCenterId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Cost Center not found with id: " + request.costCenterId()
                    ));
            entity.setCostCenter(costCenter);
        }

        resolveAndSetManager(entity);

        // Re-check amount after update
        checkPolicyLimit(entity.getAmount());

        CashAdvance saved =
                cashAdvanceRepository.save(entity);

        return cashAdvanceMapper.toResponse(saved);
    }


    // ============================================================
    // GET BY ID
    // ============================================================

    @Override
    @Transactional(readOnly = true)
    public CashAdvanceResponse getById(UUID advanceId) {

        CashAdvance advance = findEntity(advanceId);
        assertCanView(advance);
        return cashAdvanceMapper.toResponse(advance);
    }


    // ============================================================
    // GET ALL
    // ============================================================

    @Override
    @Transactional(readOnly = true)
    public List<CashAdvanceResponse> getAll() {

        return cashAdvanceRepository
                .findAll()
                .stream()
                .map(cashAdvanceMapper::toResponse)
                .toList();
    }


    // ============================================================
    // MY ADVANCES
    // ============================================================

    @Override
    @Transactional(readOnly = true)
    public Page<CashAdvanceResponse> getMyAdvances(
            String employeeId,
            String status,
            Pageable pageable) {

        String empId =
                (employeeId != null && !employeeId.isBlank())
                        ? employeeId
                        : currentUserService.getEmployeeId();

        Page<CashAdvance> page;
        if (status != null && !status.isBlank()) {
            page = cashAdvanceRepository
                    .findByEmployeeIdAndStatus(
                            empId,
                            status.toUpperCase(),
                            pageable
                    );
        } else {
            page = cashAdvanceRepository
                    .findByEmployeeId(empId, pageable);
        }

        return page.map(cashAdvanceMapper::toResponse);
    }


    // ============================================================
    // MY APPROVALS
    // ============================================================

    @Override
    @Transactional(readOnly = true)
    public Page<CashAdvanceResponse> getMyApprovals(
            String managerId,
            String status,
            Pageable pageable) {

        String mgrId =
                (managerId != null && !managerId.isBlank())
                        ? managerId
                        : currentUserService.getEmployeeId();

        Set<String> approverIds =
                new HashSet<>(
                        delegationService
                                .resolveApproverIdsActingFor(mgrId)
                );

        if (mgrId != null) {

            employeeCacheRepository
                    .findByEmployeeId(mgrId)
                    .map(EmployeeCache::getEmployeeUuid)
                    .ifPresent(approverIds::add);

            employeeCacheRepository
                    .findByEmployeeUuid(mgrId)
                    .map(EmployeeCache::getEmployeeId)
                    .ifPresent(approverIds::add);
        }

        String upperStatus =
                status != null
                        ? status.toUpperCase().trim()
                        : "";

        List<CashAdvance> allAdvances;

        if ("APPROVED".equals(upperStatus)) {

            allAdvances =
                    cashAdvanceRepository.findByStatusIn(
                            List.of(
                                    "APPROVED",
                                    "DISBURSED",
                                    "IN_PROGRESS",
                                    "RECONCILIATION_PENDING",
                                    "SUBMITTED_FOR_REVIEW",
                                    "UNDER_REVIEW",
                                    "SETTLEMENT_PENDING",
                                    "CLOSED",
                                    "SETTLED"
                            )
                    );

        } else if ("REJECTED".equals(upperStatus)) {

            allAdvances =
                    cashAdvanceRepository.findByStatus("REJECTED");

        } else {

            List<String> targetStatuses =
                    List.of(
                            "SUBMITTED",
                            "PENDING_APPROVAL",
                            "PENDING",
                            "PENDING_MANAGER_APPROVAL",
                            "PENDING_COST_CENTER_APPROVAL",
                            "PENDING_COST_CENTER",
                            "PENDING_FINANCE_APPROVAL",
                            "PENDING_FINANCE"
                    );

            if (isFinanceCaller() || isAdminCaller()) {

                allAdvances =
                        cashAdvanceRepository
                                .findByStatusIn(targetStatuses);

            } else {

                List<CashAdvance> candidateAdvances =
                        cashAdvanceRepository
                                .findByStatusIn(targetStatuses);

                allAdvances =
                        candidateAdvances
                                .stream()
                                .filter(
                                        adv ->
                                                isApproverForAdvance(
                                                        adv,
                                                        approverIds
                                                )
                                )
                                .toList();
            }
        }

        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), allAdvances.size());
        List<CashAdvanceResponse> pageContent = allAdvances
                .subList(start, end)
                .stream()
                .map(cashAdvanceMapper::toResponse)
                .toList();

        return new PageImpl<>(pageContent, pageable, allAdvances.size());
    }


    // ============================================================
    // APPROVER CHECK
    // ============================================================

    private boolean isApproverForAdvance(
            CashAdvance adv,
            Set<String> approverIds) {

        if (adv == null
                || approverIds == null
                || approverIds.isEmpty()) {

            return false;
        }

        String statusUpper =
                adv.getStatus() != null
                        ? adv.getStatus().toUpperCase()
                        : "";

        if (List.of(
                "SUBMITTED",
                "PENDING",
                "PENDING_APPROVAL",
                "PENDING_MANAGER_APPROVAL"
        ).contains(statusUpper)) {

            if (adv.getManagerId() != null
                    && approverIds.contains(adv.getManagerId())) {

                return true;
            }

            if (adv.getEmployeeId() != null) {

                String mgr =
                        employeeCacheRepository
                                .findByEmployeeId(adv.getEmployeeId())
                                .or(() ->
                                        employeeCacheRepository
                                                .findByEmployeeUuid(
                                                        adv.getEmployeeId()
                                                )
                                )
                                .map(EmployeeCache::getManagerEmployeeId)
                                .orElse(null);

                if (mgr != null
                        && approverIds.contains(mgr)) {

                    if (adv.getManagerId() == null
                            || adv.getManagerId().isBlank()) {

                        adv.setManagerId(mgr);

                        cashAdvanceRepository.save(adv);
                    }

                    return true;
                }
            }

        } else if (List.of(
                "PENDING_COST_CENTER_APPROVAL",
                "PENDING_COST_CENTER"
        ).contains(statusUpper)) {

            String ccOwner =
                    resolveCostCenterOwner(adv);

            if (ccOwner != null
                    && approverIds.contains(ccOwner)) {

                return true;
            }

            if (adv.getManagerId() != null
                    && approverIds.contains(adv.getManagerId())) {

                return true;
            }

        } else if (List.of(
                "PENDING_FINANCE_APPROVAL",
                "PENDING_FINANCE"
        ).contains(statusUpper)) {

            return isFinanceCaller() || isAdminCaller();
        }

        return false;
    }


    // ============================================================
    // FILTERED
    // ============================================================

    @Override
    @Transactional(readOnly = true)
    public Page<CashAdvanceResponse> getFiltered(
            String employeeId,
            String status,
            Pageable pageable) {

        // Finance and Admin may list across employees. Everyone else sees only their own advances, and any
        // employeeId they pass is ignored.
        if (!isFinanceCaller() && !isAdminCaller()) {
            return getMyAdvances(currentUserService.getEmployeeId(), status, pageable);
        }

        if (employeeId != null && !employeeId.isBlank()) {

            return getMyAdvances(employeeId, status, pageable);
        }

        if (status != null && !status.isBlank()) {

            return cashAdvanceRepository
                    .findByStatus(status.toUpperCase(), pageable)
                    .map(cashAdvanceMapper::toResponse);
        }

        List<CashAdvanceResponse> allResponses = getAll();
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), allResponses.size());
        return new PageImpl<>(allResponses.subList(start, end), pageable, allResponses.size());
    }


    // ============================================================
    // SUBMIT
    // ============================================================

    @Override
    public CashAdvanceResponse submit(UUID advanceId) {

        CashAdvance entity =
                findEntity(advanceId);

        if ("SUBMITTED".equalsIgnoreCase(entity.getStatus())
                || "PENDING_APPROVAL".equalsIgnoreCase(entity.getStatus())
                || "PENDING_COST_CENTER_APPROVAL".equalsIgnoreCase(entity.getStatus())
                || "PENDING_FINANCE_APPROVAL".equalsIgnoreCase(entity.getStatus())) {

            throw new BusinessRuleViolationException(
                    "Cash advance is already submitted."
            );
        }

        if (!"DRAFT".equalsIgnoreCase(entity.getStatus())
                && !"REJECTED".equalsIgnoreCase(entity.getStatus())
                && !"DEFERRED".equalsIgnoreCase(entity.getStatus())) {

            throw new BusinessRuleViolationException(
                    "Only DRAFT, REJECTED or DEFERRED cash advances can be submitted."
            );
        }

        if (entity.getSettlementDueDate() == null
                || !entity.getSettlementDueDate()
                        .isAfter(LocalDate.now(clock))) {

            throw new BusinessRuleViolationException(
                    "Settlement due date must be a future date."
            );
        }

        resolveAndSetManager(entity);


        // IMPORTANT: block submission when amount exceeds configured limit
        checkPolicyLimit(entity.getAmount());

        String oldStatus = entity.getStatus();

        entity.setStatus("SUBMITTED");
        // The reason for the last rejection or deferral is shown until the request goes back to the manager.
        entity.setRejectionReason(null);

        CashAdvance saved =
                cashAdvanceRepository.save(entity);

        String actingUser =
                currentUserService.getEmployeeId();

        auditLogRepository.save(
                AuditLog.builder()
                        .entityName("CashAdvance")
                        .entityId(saved.getAdvanceId())
                        .action("CASH_ADVANCE_SUBMITTED")
                        .oldValue(oldStatus)
                        .newValue("SUBMITTED")
                        .performedBy(actingUser)
                        .performedAt(LocalDateTime.now())
                        .build()
        );

        approvalEventPublisher.publish(
                "CASH_ADVANCE_SUBMITTED",
                saved.getAdvanceId(),
                "employeeId="
                        + saved.getEmployeeId()
                        + ", managerId="
                        + saved.getManagerId()
        );

        notifyByEmail(() -> cashAdvanceEmailService.onSubmitted(saved));

        return cashAdvanceMapper.toResponse(saved);
    }


    // ============================================================
    // APPROVE
    // ============================================================

    /** Queues a cash advance email. A failure is logged and never undoes the submission or approval. */
    private void notifyByEmail(Runnable queueEmail) {
        try {
            queueEmail.run();
        } catch (RuntimeException ex) {
            log.warn("Cash advance email could not be queued: {}", ex.getMessage(), ex);
        }
    }

    /**
     * Finance's approval of a Cash Advance + Outstanding request. In one transaction: the rollover is taken from the eligible
     * balances (oldest first), the approved cash is set (it may be lower than requested), any overdue exception is recorded,
     * and then the request is approved. If the rollover cannot be applied, nothing is approved.
     */
    @Override
    public CashAdvanceResponse approveAllocation(UUID advanceId, String approverId,
                                                 com.expense_management_service.dto.request.CashAdvanceAllocationApprovalRequest request) {
        cashAdvanceRepository.findByIdForUpdate(advanceId);
        CashAdvance entity = findEntity(advanceId);
        if (!CashAdvanceRequestType.ADVANCE_PLUS_OUTSTANDING.name().equals(entity.getRequestType())) {
            throw new BusinessRuleViolationException("Only a Cash Advance + Outstanding request is approved through an allocation.");
        }
        String statusUpper = entity.getStatus() == null ? "" : entity.getStatus().toUpperCase();
        if (!List.of("PENDING_FINANCE_APPROVAL", "PENDING_FINANCE").contains(statusUpper)) {
            throw new BusinessRuleViolationException("This request is not awaiting Finance approval. Current status: " + entity.getStatus());
        }
        validateApproverAuthorization(entity, approverId);

        BigDecimal requestedCash = entity.getAdditionalAmountRequested();
        if (requestedCash == null) {
            throw new BusinessRuleViolationException("The additional amount is missing on this request.");
        }
        BigDecimal cash = request.approvedCashAmount() != null ? request.approvedCashAmount() : requestedCash;
        if (cash.signum() < 0 || cash.compareTo(requestedCash) > 0) {
            throw new BusinessRuleViolationException("The approved cash must be between 0 and the requested " + requestedCash.toPlainString() + ".");
        }
        if (cash.signum() > 0) {
            validateRoundAmount(cash);
        }

        // The eligible balance is read here, under the lock, not from the employee's earlier figures.
        BigDecimal eligible = allocationFor(entity.getEmployeeId(), CashAdvanceRequestType.ADVANCE_PLUS_OUTSTANDING, requestedCash)
                .eligibleOutstanding();
        CashAdvanceCarryForwardService.OverdueException overdue = null;
        if (request.overdueSourceAdvanceId() != null) {
            if (!hasAuthority(RoleConstants.ROLE_FINANCE_EXECUTIVE)) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Only a Finance executive can authorise a rollover from an overdue advance.");
            }
            overdue = new CashAdvanceCarryForwardService.OverdueException(
                    request.overdueSourceAdvanceId(), request.overdueAmount(), request.overdueJustification());
        }
        BigDecimal maxRollover = overdue != null && overdue.amount() != null ? eligible.add(overdue.amount()) : eligible;
        BigDecimal rollover = request.rolloverAmount() != null ? request.rolloverAmount() : maxRollover;
        if (rollover.signum() <= 0) {
            throw new BusinessRuleViolationException("There is no eligible outstanding balance to roll over.");
        }
        if (rollover.compareTo(maxRollover) > 0) {
            throw new BusinessRuleViolationException("The rollover is more than the eligible balance of " + maxRollover.toPlainString() + ".");
        }
        if (rollover.compareTo(maxRollover) < 0
                && (request.reductionJustification() == null || request.reductionJustification().isBlank())) {
            throw new BusinessRuleViolationException(
                    "Explain why the rollover is less than the eligible balance. The rest stays outstanding on its advance.");
        }

        carryForwardService.applyRollover(entity, rollover, cash, overdue, approverId,
                "Rolled over on Finance approval of " + advanceId, hasAuthority(RoleConstants.ROLE_FINANCE_EXECUTIVE));
        return approveInternal(advanceId, approverId, overdue == null ? null : overdue.sourceAdvanceId());
    }

    private static boolean hasAuthority(String role) {
        org.springframework.security.core.Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority()));
    }

    @Override
    public CashAdvanceResponse approve(
            UUID advanceId,
            String approverId) {
        return approveInternal(advanceId, approverId, null);
    }

    /** The shared approval. {@code exemptAdvanceId} is an overdue advance Finance has explicitly authorised to roll over. */
    private CashAdvanceResponse approveInternal(UUID advanceId, String approverId, UUID exemptAdvanceId) {

        // Serialise on this advance: a concurrent change waits here, so state checks below run on settled data.
        cashAdvanceRepository.findByIdForUpdate(advanceId);
        CashAdvance entity =
                findEntity(advanceId);

        String statusUpper =
                entity.getStatus() != null
                        ? entity.getStatus().toUpperCase()
                        : "";

        if ("APPROVED".equalsIgnoreCase(statusUpper)) {

            throw new BusinessRuleViolationException(
                    "Cash advance is already approved."
            );
        }

        if ("DISBURSED".equalsIgnoreCase(statusUpper)) {

            throw new BusinessRuleViolationException(
                    "Cash advance is already disbursed."
            );
        }

        List<String> pendingStatuses =
                List.of(
                        "SUBMITTED",
                        "PENDING_APPROVAL",
                        "PENDING",
                        "PENDING_MANAGER_APPROVAL",
                        "PENDING_COST_CENTER_APPROVAL",
                        "PENDING_COST_CENTER",
                        "PENDING_FINANCE_APPROVAL",
                        "PENDING_FINANCE"
                );

        if (!pendingStatuses.contains(statusUpper)) {

            throw new BusinessRuleViolationException(
                    "Only pending cash advances can be approved."
            );
        }

        // Existing authorization remains unchanged
        validateApproverAuthorization(
                entity,
                approverId
        );

        /*
         * Re-check amount at approval time only when the record actually contains one.
         * Historical / partially-populated approval records can legitimately reach this
         * path without a value set, and the service should not reject a valid status
         * transition solely because the amount field was blank on an older row.
         */
        if (entity.getAmount() != null) {
            checkPolicyLimit(entity.getAmount());
        }

        /*
         * Overdue advances are routed to Finance. The request is approved at the Finance stage only if every overdue
         * balance is either settled or covered by an explicit Finance executive authorisation (the exempt advance).
         */
        if (List.of("PENDING_FINANCE_APPROVAL", "PENDING_FINANCE").contains(statusUpper)) {
            checkOverdueAdvances(entity.getEmployeeId(), exemptAdvanceId);
        }

        String oldStatus = entity.getStatus();

        String actingUser =
                (approverId != null && !approverId.isBlank())
                        ? approverId
                        : currentUserService.getEmployeeId();

        String nextStatus;

        if (List.of(
                "SUBMITTED",
                "PENDING_APPROVAL",
                "PENDING",
                "PENDING_MANAGER_APPROVAL"
        ).contains(statusUpper)) {

            String ccOwner =
                    resolveCostCenterOwner(entity);

            if (ccOwner != null
                    && !ccOwner.isBlank()
                    && !ccOwner.equalsIgnoreCase(actingUser)
                    && !ccOwner.equalsIgnoreCase(entity.getEmployeeId())
                    && !ccOwner.equalsIgnoreCase(entity.getManagerId())) {

                nextStatus =
                        "PENDING_COST_CENTER_APPROVAL";

            } else {

                nextStatus =
                        "PENDING_FINANCE_APPROVAL";
            }

        } else if (List.of(
                "PENDING_COST_CENTER_APPROVAL",
                "PENDING_COST_CENTER"
        ).contains(statusUpper)) {

            nextStatus =
                    "PENDING_FINANCE_APPROVAL";

        } else {

            // Cash Advance + Outstanding needs the rollover step before it can be approved. Until that exists, refuse it
            // here so no cash can be disbursed without the balance being rolled over.
            if (CashAdvanceRequestType.ADVANCE_PLUS_OUTSTANDING.name().equals(entity.getRequestType())
                    && entity.getAuthorizedRolloverAmount() == null) {
                throw new BusinessRuleViolationException(
                        "A Cash Advance + Outstanding request is approved through the Finance allocation approval, which sets its rollover.");
            }
            // Final Finance/Admin approval consumes the selected Cost Center budget.
            // Manager and Cost Center approvals only advance the workflow.
            consumeCostCenterBudget(entity);

            nextStatus = "APPROVED";
            // Freeze the approved amount. Disbursement pays this, and Finance cannot change it.
            if (entity.getAmount() != null) {
                entity.setApprovedAmount(entity.getAmount());
            }
            // Final approval is Finance's authorization of any exposure exception the manager recorded.
            if (entity.getExposureExceptionJustification() != null && entity.getExposureExceptionAuthorizedBy() == null) {
                entity.setExposureExceptionAuthorizedBy(actingUser);
                entity.setExposureExceptionAuthorizedAt(LocalDateTime.now());
            }
        }

        entity.setStatus(nextStatus);

        CashAdvance saved =
                cashAdvanceRepository.save(entity);

        if ("APPROVED".equals(nextStatus) && saved.getRequestType() == null) {
            // Legacy requests only. Requests of the new flow get their rollover explicitly, never from a ready balance.
            carryForwardService.applyReadyForDestination(saved);
        }

        auditLogRepository.save(
                AuditLog.builder()
                        .entityName("CashAdvance")
                        .entityId(saved.getAdvanceId())
                        .action("CASH_ADVANCE_APPROVED")
                        .oldValue(oldStatus)
                        .newValue(nextStatus)
                        .performedBy(actingUser)
                        .performedAt(LocalDateTime.now())
                        .build()
        );

        approvalEventPublisher.publish(
                "CASH_ADVANCE_APPROVED",
                saved.getAdvanceId(),
                "approvedBy="
                        + actingUser
                        + ", nextStatus="
                        + nextStatus
        );

        if ("PENDING_COST_CENTER_APPROVAL".equals(nextStatus)) {
            notifyByEmail(() -> cashAdvanceEmailService.onCostCenterStage(saved));
        } else if ("PENDING_FINANCE_APPROVAL".equals(nextStatus)) {
            notifyByEmail(() -> cashAdvanceEmailService.onFinanceStage(saved));
        }

        return cashAdvanceMapper.toResponse(saved);
    }


    // ============================================================
    // REJECT
    // ============================================================

    @Override
    public CashAdvanceResponse reject(
            UUID advanceId,
            String approverId,
            String reason) {

        if (reason == null || reason.isBlank()) {

            throw new BusinessRuleViolationException(
                    "Rejection reason is mandatory when rejecting a cash advance"
            );
        }

        CashAdvance entity =
                findEntity(advanceId);

        String statusUpper =
                entity.getStatus() != null
                        ? entity.getStatus().toUpperCase()
                        : "";

        if ("REJECTED".equalsIgnoreCase(statusUpper)) {

            throw new BusinessRuleViolationException(
                    "Cash advance is already rejected."
            );
        }

        if ("DISBURSED".equalsIgnoreCase(statusUpper)) {

            throw new BusinessRuleViolationException(
                    "Cash advance is already disbursed."
            );
        }

        List<String> pendingStatuses =
                List.of(
                        "SUBMITTED",
                        "PENDING_APPROVAL",
                        "PENDING",
                        "PENDING_MANAGER_APPROVAL",
                        "PENDING_COST_CENTER_APPROVAL",
                        "PENDING_COST_CENTER",
                        "PENDING_FINANCE_APPROVAL",
                        "PENDING_FINANCE"
                );

        if (!pendingStatuses.contains(statusUpper)) {

            throw new BusinessRuleViolationException(
                    "Only pending cash advances can be rejected."
            );
        }

        validateApproverAuthorization(
                entity,
                approverId
        );

        String oldStatus = entity.getStatus();

        entity.setStatus("REJECTED");
        entity.setRejectionReason(reason.trim());

        CashAdvance saved =
                cashAdvanceRepository.save(entity);

        String actingUser =
                (approverId != null && !approverId.isBlank())
                        ? approverId
                        : currentUserService.getEmployeeId();

        auditLogRepository.save(
                AuditLog.builder()
                        .entityName("CashAdvance")
                        .entityId(saved.getAdvanceId())
                        .action("CASH_ADVANCE_REJECTED")
                        .oldValue(oldStatus)
                        .newValue("REJECTED: " + reason)
                        .performedBy(actingUser)
                        .performedAt(LocalDateTime.now())
                        .build()
        );

        approvalEventPublisher.publish(
                "CASH_ADVANCE_REJECTED",
                saved.getAdvanceId(),
                "rejectedBy="
                        + actingUser
                        + ", reason="
                        + reason
        );

        return cashAdvanceMapper.toResponse(saved);
    }


    // ============================================================
    // DISBURSE
    // ============================================================

    @Override
    public CashAdvanceResponse disburse(
            UUID advanceId,
            String financeUserId,
            CashAdvanceDisbursement disbursement) {

        // Serialise on this advance: a concurrent change waits here, so state checks below run on settled data.
        cashAdvanceRepository.findByIdForUpdate(advanceId);
        CashAdvance entity =
                findEntity(advanceId);

        String currentStatusUpper =
                entity.getStatus() != null
                        ? entity.getStatus().toUpperCase()
                        : "";

        if ("DISBURSED".equalsIgnoreCase(currentStatusUpper)
                || "IN_PROGRESS".equalsIgnoreCase(currentStatusUpper)) {

            throw new BusinessRuleViolationException(
                    "Cash advance is already disbursed."
            );
        }

        if (!"APPROVED".equalsIgnoreCase(currentStatusUpper)) {

            throw new BusinessRuleViolationException(
                    "Only APPROVED cash advances can be disbursed."
            );
        }

        if (CashAdvanceRequestType.ADVANCE_PLUS_OUTSTANDING.name().equals(entity.getRequestType())
                && entity.getAuthorizedRolloverAmount() == null) {
            throw new BusinessRuleViolationException(
                    "A Cash Advance + Outstanding request cannot be disbursed until its rollover is authorised.");
        }
        // A proposed carry-forward into this advance is not yet money the employee already holds. Pay nothing until it is decided.
        carryForwardService.assertNoPendingProposalInto(entity.getAdvanceId());
        BigDecimal carriedIn = CashAdvanceBalanceRules.carriedIn(entity);
        if ("SET_OFF".equals(entity.getRecommendedTreatment())) {
            BigDecimal recommended = entity.getRecommendedSetOffAmount() == null ? BigDecimal.ZERO : entity.getRecommendedSetOffAmount();
            if (carriedIn.compareTo(recommended) < 0) {
                throw new BusinessRuleViolationException("The manager recommended a set-off of " + recommended
                        + " from advance " + entity.getRecommendedSourceAdvanceId()
                        + ", but it is not applied yet (applied: " + carriedIn + "). Finance must propose and approve the carry-forward before disbursement.");
            }
        }
        if (carriedIn.signum() > 0 && entity.getAmount().subtract(carriedIn).signum() <= 0) {
            // The carried-in balance covers the whole advance, so nothing is paid in cash. Record it as a carry-forward.
            return disburseCarriedForward(entity, financeUserId);
        }

        // Validate the confirmation before anything is stored or changed, so a rejected request leaves no trace.
        CashAdvanceDisbursementRules.validate(disbursement, LocalDate.now(clock));
        String mode = CashAdvanceDisbursementRules.normalizedMode(disbursement.disbursementMode());

        String proofKey = null;
        if (CashAdvanceDisbursementRules.CASH.equals(mode)) {
            proofKey = storeProof("disbursements", advanceId, mode, disbursement.signedAcknowledgment());
        } else if (CashAdvanceDisbursementRules.UPI.equals(mode)) {
            proofKey = storeProof("disbursements", advanceId, mode, disbursement.paymentProof());
        }

        String oldStatus = entity.getStatus();

        entity.setStatus("IN_PROGRESS");
        // The 30-day expense window starts here (CashAdvanceExpenseWindow reads this field).
        entity.setDisbursedAt(LocalDateTime.now());
        applyDisbursementDetails(entity, disbursement, mode, proofKey);
        entity.setDisbursedAmount(entity.getAmount().subtract(carriedIn));

        if (entity.getOutstandingBalance() == null) {
            // The obligation is the advance amount. Any carried-in balance is already part of it (carry-forward).
            entity.setOutstandingBalance(entity.getAmount());
        }

        CashAdvance saved =
                cashAdvanceRepository.save(entity);

        String actingUser =
                (financeUserId != null && !financeUserId.isBlank())
                        ? financeUserId
                        : currentUserService.getEmployeeId();

        auditLogRepository.save(
                AuditLog.builder()
                        .entityName("CashAdvance")
                        .entityId(saved.getAdvanceId())
                        .action("CASH_ADVANCE_DISBURSED")
                        .oldValue(oldStatus)
                        .newValue("IN_PROGRESS")
                        .performedBy(actingUser)
                        .performedAt(LocalDateTime.now())
                        .build()
        );

        approvalEventPublisher.publish(
                "CASH_ADVANCE_DISBURSED",
                saved.getAdvanceId(),
                "disbursedBy=" + actingUser + ",mode=" + mode
        );

        return cashAdvanceMapper.toResponse(saved);
    }

    /** Disbursement of an advance fully covered by carried-in balance: no cash, no payment details. */
    private CashAdvanceResponse disburseCarriedForward(CashAdvance entity, String financeUserId) {
        String oldStatus = entity.getStatus();
        entity.setStatus("IN_PROGRESS");
        entity.setDisbursedAt(LocalDateTime.now());
        entity.setDisbursementMode(CARRIED_FORWARD_MODE);
        entity.setDisbursementPaymentDate(LocalDate.now(clock));
        entity.setDisbursedAmount(BigDecimal.ZERO);
        if (entity.getOutstandingBalance() == null) {
            entity.setOutstandingBalance(entity.getAmount());
        }
        CashAdvance saved = cashAdvanceRepository.save(entity);
        String actingUser = (financeUserId != null && !financeUserId.isBlank())
                ? financeUserId
                : currentUserService.getEmployeeId();
        auditLogRepository.save(
                AuditLog.builder()
                        .entityName("CashAdvance")
                        .entityId(saved.getAdvanceId())
                        .action("CASH_ADVANCE_DISBURSED")
                        .oldValue(oldStatus)
                        .newValue("IN_PROGRESS, carried forward, no cash paid")
                        .performedBy(actingUser)
                        .performedAt(LocalDateTime.now())
                        .build());
        return cashAdvanceMapper.toResponse(saved);
    }

    /** Stores an uploaded proof under a unique key and returns that key. Callers only pass validated files. */
    private String storeProof(String folder, UUID advanceId, String mode, MultipartFile file) {
        String objectKey = folder + "/" + advanceId + "/"
                + mode.toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID() + "."
                + CashAdvanceDisbursementRules.proofExtension(file);
        storageService.upload(objectKey, file);
        return objectKey;
    }

    /** Copies only the fields that belong to the chosen mode, so the record never mixes cash and transfer details. */
    private static void applyDisbursementDetails(
            CashAdvance entity,
            CashAdvanceDisbursement disbursement,
            String mode,
            String proofKey) {

        entity.setDisbursementMode(mode);
        entity.setDisbursementConfirmationNote(
                disbursement.confirmationNote() == null || disbursement.confirmationNote().isBlank()
                        ? null
                        : disbursement.confirmationNote().trim());

        switch (mode) {
            case CashAdvanceDisbursementRules.CASH -> {
                entity.setDisbursementAcknowledgedBy(disbursement.receivedByName().trim());
                entity.setDisbursementProofReference(proofKey);
            }
            case CashAdvanceDisbursementRules.BANK_TRANSFER -> {
                entity.setDisbursementReference(disbursement.transactionReference().trim());
                entity.setDisbursementPaymentDate(disbursement.transferDate());
                entity.setDisbursementTransferDetails(disbursement.transferDetails().trim());
            }
            case CashAdvanceDisbursementRules.UPI -> {
                entity.setDisbursementReference(disbursement.upiTransactionId().trim());
                entity.setDisbursementPayeeUpiId(disbursement.payeeUpiId().trim());
                entity.setDisbursementPaymentDate(disbursement.transferDate());
                entity.setDisbursementProofReference(proofKey);
            }
            default -> throw new IllegalStateException("Unhandled disbursement mode: " + mode);
        }
    }


    // ============================================================
    // REPAY
    // ============================================================

    @Override
    public CashAdvanceRepaymentResponse repay(
            CashAdvanceRepaymentRequest request) {

        if (!isFinanceCaller() && !isAdminCaller()) {

            throw new BusinessRuleViolationException(
                    "Only Finance Executives can process cash advance settlements"
            );
        }

        // Row lock: two repayments on the same advance run one after another, so each sees the balance the other left.
        CashAdvance advance =
                cashAdvanceRepository.findByIdForUpdate(request.advanceId())
                        .orElseThrow(() -> new ResourceNotFoundException("Cash advance not found: " + request.advanceId()));

        // Everything is checked before anything is stored or changed, so a rejected request leaves no trace.
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new IllegalArgumentException("Enter a repayment amount greater than zero.");
        }
        CashAdvanceDisbursementRules.validate(request.payment(), LocalDate.now(clock));
        String mode = CashAdvanceDisbursementRules.normalizedMode(request.payment().disbursementMode());
        String reference = repaymentReference(mode, request.payment());

        if (reference != null
                && cashAdvanceRepaymentRepository.existsByCashAdvance_AdvanceIdAndPaymentReference(request.advanceId(), reference)) {
            throw new BusinessRuleViolationException("A repayment with reference " + reference + " is already recorded for this advance.");
        }

        String currentStatusUpper =
                advance.getStatus() != null
                        ? advance.getStatus().toUpperCase()
                        : "";

        List<String> allowedRepayStatuses =
                List.of(
                        "DISBURSED",
                        "IN_PROGRESS",
                        "RECONCILIATION_PENDING",
                        "SUBMITTED_FOR_REVIEW",
                        "UNDER_REVIEW",
                        "APPROVED",
                        "SETTLEMENT_PENDING",
                        "EXPENSE_SUBMITTED",
                        "EXPENSE_VERIFIED",
                        "ADJUSTED",
                        "PARTIALLY_SETTLED",
                        "PARTIALLY_ADJUSTED",
                        "OVERDUE"
                );

        if (!allowedRepayStatuses.contains(currentStatusUpper)) {

            throw new BusinessRuleViolationException(
                    "Cannot record repayment for cash advance in status: "
                            + advance.getStatus()
            );
        }

        // The authoritative balance from the records, not the cached column.
        BigDecimal currentBalance = balanceCalculator.outstanding(advance);

        if (currentBalance.compareTo(BigDecimal.ZERO) <= 0) {

            throw new BusinessRuleViolationException(
                    "Cash advance outstanding balance is already zero."
            );
        }

        if (request.amount().compareTo(currentBalance) > 0) {

            throw new BusinessRuleViolationException(
                    "Repayment amount ("
                            + request.amount()
                            + ") cannot exceed outstanding balance ("
                            + currentBalance
                            + ")"
            );
        }

        int decimals = advance.getCurrency() != null ? advance.getCurrency().getDecimalPlaces() : 2;
        BigDecimal newBalance =
                CashAdvanceBalanceRules.requireNonNegative(currentBalance.subtract(request.amount()), decimals);

        String proofKey = storeRepaymentProof(request.advanceId(), mode, request.payment());

        advance.setOutstandingBalance(newBalance);

        String previousStatus =
                advance.getStatus();

        advance.setStatus(cashAdvanceBalanceLedger.statusFor(advance, newBalance, decimals));
        cashAdvanceBudgetMovements.releaseAllocation(advance, request.amount());

        cashAdvanceRepository.save(advance);
        // Repaid in full: any open refund notice for this advance is closed now, not at the next scheduled run.
        cashAdvanceRecoveryService.resolveNoticesIfSettled(advance.getAdvanceId(), newBalance, LocalDateTime.now());

        CashAdvanceRepayment repayment =
                cashAdvanceRepaymentMapper.toEntity(request, mode, reference, proofKey);

        repayment.setCashAdvance(advance);

        String actingUser =
                currentUserService.getEmployeeId();

        repayment.setRepaidBy(actingUser);
        repayment.setRepaidAt(LocalDateTime.now());

        CashAdvanceRepayment saved =
                cashAdvanceRepaymentRepository.save(repayment);

        auditLogRepository.save(
                AuditLog.builder()
                        .entityName("CashAdvanceRepayment")
                        .entityId(saved.getRepaymentId())
                        .action("REPAYMENT_RECORDED")
                        .oldValue(
                                "Balance: "
                                        + currentBalance
                                        + ", Status: "
                                        + previousStatus
                        )
                        .newValue(
                                "Repaid: "
                                        + request.amount()
                                        + ", NewBalance: "
                                        + newBalance
                                        + ", Status: "
                                        + advance.getStatus()
                        )
                        .performedBy(actingUser)
                        .performedAt(LocalDateTime.now())
                        .build()
        );

        approvalEventPublisher.publish(
                "CASH_ADVANCE_REPAID",
                advance.getAdvanceId(),
                "amount=" + request.amount()
        );

        if ("CLOSED".equalsIgnoreCase(advance.getStatus())
                || "SETTLED".equalsIgnoreCase(advance.getStatus())) {

            approvalEventPublisher.publish(
                    "CASH_ADVANCE_SETTLED",
                    advance.getAdvanceId(),
                    "finalBalance=0.0000"
            );
        }

        return cashAdvanceRepaymentMapper.toResponse(saved);
    }

    /**
     * The reference used for the duplicate check. Bank transfers and UPI use the transaction reference. Cash has no
     * bank reference, so it uses a fingerprint of the signed acknowledgment: the same document cannot be recorded
     * twice against one advance.
     */
    private static String repaymentReference(String mode, CashAdvanceDisbursement payment) {
        return switch (mode) {
            case CashAdvanceDisbursementRules.BANK_TRANSFER -> payment.transactionReference().trim();
            case CashAdvanceDisbursementRules.UPI -> payment.upiTransactionId().trim();
            case CashAdvanceDisbursementRules.CASH -> "CASH-" + sha256Prefix(payment.signedAcknowledgment());
            default -> throw new IllegalStateException("Unhandled repayment mode: " + mode);
        };
    }

    /** Stores the proof for a cash or UPI repayment. Bank transfers have no file, so they return null. */
    private String storeRepaymentProof(UUID advanceId, String mode, CashAdvanceDisbursement payment) {
        if (CashAdvanceDisbursementRules.CASH.equals(mode)) {
            return storeProof("repayments", advanceId, mode, payment.signedAcknowledgment());
        }
        if (CashAdvanceDisbursementRules.UPI.equals(mode)) {
            return storeProof("repayments", advanceId, mode, payment.paymentProof());
        }
        return null;
    }

    private static String sha256Prefix(MultipartFile file) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(file.getBytes());
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file", e);
        }
    }


    // ============================================================
    // SETTLEMENT
    // ============================================================

    @Override
    @Transactional(readOnly = true)
    public StoredProof disbursementProof(UUID advanceId) {
        CashAdvance advance = findEntity(advanceId);
        assertCanView(advance);
        return openProof(advance.getDisbursementProofReference(), "payment proof for the disbursement");
    }

    @Override
    @Transactional(readOnly = true)
    public StoredProof repaymentProof(UUID advanceId, UUID repaymentId) {
        CashAdvance advance = findEntity(advanceId);
        assertCanView(advance);
        CashAdvanceRepayment repayment = cashAdvanceRepaymentRepository.findById(repaymentId)
                .filter(r -> r.getCashAdvance() != null && advanceId.equals(r.getCashAdvance().getAdvanceId()))
                .orElseThrow(() -> new ResourceNotFoundException("Repayment not found on this cash advance: " + repaymentId));
        return openProof(repayment.getProofReference(), "payment proof for this repayment");
    }

    private StoredProof openProof(String objectKey, String label) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new ResourceNotFoundException("No " + label + " is on file.");
        }
        String extension = objectKey.substring(objectKey.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String contentType = switch (extension) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            default -> "application/octet-stream";
        };
        String fileName = objectKey.substring(objectKey.lastIndexOf('/') + 1);
        return new StoredProof(fileName, contentType, storageService.download(objectKey));
    }

    @Override
    @Transactional(readOnly = true)
    public CashAdvanceSettlementResponse getSettlement(
            UUID advanceId) {

        CashAdvance advance =
                findEntity(advanceId);
        assertCanView(advance);

        List<CashAdvanceAdjustment> adjustments =
                cashAdvanceAdjustmentRepository
                        .findByCashAdvance_AdvanceId(advanceId);

        List<CashAdvanceRepayment> repayments =
                cashAdvanceRepaymentRepository
                        .findByCashAdvance_AdvanceId(advanceId);

        BigDecimal totalAdjusted =
                adjustments
                        .stream()
                        .filter(a -> a.isActive())
                        .map(CashAdvanceAdjustment::getAdjustedAmount)
                        .filter(a -> a != null)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        BigDecimal totalRepaid =
                repayments
                        .stream()
                        .map(CashAdvanceRepayment::getAmount)
                        .filter(r -> r != null)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        // Same formula as the ledger: the amount, less what was carried forward, adjusted and repaid (salary deduction included).
        BigDecimal calculatedBalance = CashAdvanceBalanceRules.ledgerBalance(advance, totalAdjusted, totalRepaid);

        BigDecimal repaymentAmount =
                calculatedBalance.compareTo(BigDecimal.ZERO) > 0
                        ? calculatedBalance
                        : BigDecimal.ZERO;

        BigDecimal reimbursementAmount =
                calculatedBalance.compareTo(BigDecimal.ZERO) < 0
                        ? calculatedBalance.abs()
                        : BigDecimal.ZERO;

        String settlementStatus;

        if (calculatedBalance.compareTo(BigDecimal.ZERO) > 0) {

            settlementStatus = "REPAYMENT_DUE";

        } else if (calculatedBalance.compareTo(BigDecimal.ZERO) < 0) {

            settlementStatus = "REIMBURSEMENT_DUE";

        } else {

            settlementStatus = "FULLY_SETTLED";
        }

        List<CashAdvanceAdjustmentResponse> adjResponses =
                adjustments
                        .stream()
                        .map(cashAdvanceAdjustmentMapper::toResponse)
                        .toList();

        List<CashAdvanceRepaymentResponse> repResponses =
                repayments
                        .stream()
                        .map(cashAdvanceRepaymentMapper::toResponse)
                        .toList();

        return new CashAdvanceSettlementResponse(
                advance.getAdvanceId(),
                advance.getEmployeeId(),
                advance.getAmount(),
                advance.getCurrency() != null
                        ? advance.getCurrency().getCurrencyCode()
                        : null,
                totalAdjusted,
                totalRepaid,
                calculatedBalance,
                advance.getStatus(),
                advance.getSettlementDueDate(),
                repaymentAmount,
                reimbursementAmount,
                settlementStatus,
                adjResponses,
                repResponses,
                CashAdvanceDisbursementSummary.from(advance)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public CashAdvanceUtilizationResponse getUtilization(UUID advanceId) {

        CashAdvance advance = findEntity(advanceId);
        assertCanView(advance);

        return cashAdvanceUtilizationCalculator.calculate(advance);
    }


    // ============================================================
    // OVERDUE PROCESSING
    // ============================================================

    @Override
    public void processOverdueAdvances() {

        List<CashAdvance> activeAdvances =
                cashAdvanceRepository.findByStatusIn(
                        List.of(
                                "DISBURSED",
                                "IN_PROGRESS",
                                "RECONCILIATION_PENDING",
                                "SUBMITTED_FOR_REVIEW",
                                "UNDER_REVIEW",
                                "APPROVED",
                                "SETTLEMENT_PENDING",
                                "PARTIALLY_SETTLED",
                                "PARTIALLY_ADJUSTED"
                        )
                );

        LocalDateTime now = LocalDateTime.now();

        for (CashAdvance advance : activeAdvances) {

            // Overdue means: an open balance after the 30-day expense window (see CashAdvanceExpenseWindow), not the
            // user-entered settlement date. The ledger derives OVERDUE from the same rule, so the status is durable.
            if (!CashAdvanceExpenseWindow.isLocked(advance, now)
                    || balanceCalculator.outstanding(advance).compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            String oldStatus = advance.getStatus();
            if ("OVERDUE".equals(oldStatus)) {
                continue;
            }

            cashAdvanceBalanceLedger.refreshStatus(advance, "SYSTEM", "Expense window ended with an open balance");
            cashAdvanceRepository.save(advance);

            auditLogRepository.save(
                    AuditLog.builder()
                            .entityName("CashAdvance")
                            .entityId(advance.getAdvanceId())
                            .action("OVERDUE_MARKED")
                            .oldValue(oldStatus)
                            .newValue(advance.getStatus())
                            .performedBy("SYSTEM")
                            .performedAt(now)
                            .build()
            );

            approvalEventPublisher.publish(
                    "CASH_ADVANCE_OVERDUE",
                    advance.getAdvanceId(),
                    "windowEnded=" + CashAdvanceExpenseWindow.deadline(advance)
            );
        }
    }


    // ============================================================
    // CANCEL
    // ============================================================

    @Override
    public CashAdvanceResponse cancel(UUID advanceId) {

        CashAdvance entity =
                findEntity(advanceId);

        String st =
                entity.getStatus() != null
                        ? entity.getStatus().toUpperCase()
                        : "";

        if (List.of(
                "DISBURSED",
                "IN_PROGRESS",
                "CLOSED",
                "SETTLED",
                "PARTIALLY_SETTLED"
        ).contains(st)) {

            throw new BusinessRuleViolationException(
                    "Cannot cancel a cash advance that is already disbursed or settled."
            );
        }

        entity.setStatus("CANCELLED");

        return cashAdvanceMapper.toResponse(
                cashAdvanceRepository.save(entity)
        );
    }


    // ============================================================
    // DELETE
    // ============================================================

    @Override
    public void delete(UUID advanceId) {

        cashAdvanceRepository.delete(
                findEntity(advanceId)
        );
    }


    /** Advances are requested in whole blocks of this amount (the base currency). Change it here only. */
    public static final BigDecimal ADVANCE_ROUND_BLOCK = new BigDecimal("500");

    private static void validateRoundAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0
                || amount.remainder(ADVANCE_ROUND_BLOCK).signum() != 0) {
            throw new BusinessRuleViolationException(
                    "The advance amount must be a positive multiple of " + ADVANCE_ROUND_BLOCK.toPlainString() + ".");
        }
    }

    // ============================================================
    // DATE VALIDATION
    // ============================================================

    private LocalDate validateAndExtractFutureDate(
            CashAdvanceRequest request) {

        LocalDate dueDate =
                request.neededByDate() != null
                        ? request.neededByDate()
                        : request.settlementDueDate();

        if (dueDate == null) {

            throw new BusinessRuleViolationException(
                    "Needed by date is required and must be in the future."
            );
        }

        if (!dueDate.isAfter(LocalDate.now(clock))) {

            throw new BusinessRuleViolationException(
                    "Needed by date must be a future date."
            );
        }

        return dueDate;
    }


    // ============================================================
    // MANAGER
    // ============================================================

    private void resolveAndSetManager(
            CashAdvance entity) {

        if (entity.getEmployeeId() != null
                && !entity.getEmployeeId().isBlank()) {

            employeeCacheRepository
                    .findByEmployeeId(entity.getEmployeeId())
                    .or(() ->
                            employeeCacheRepository
                                    .findByEmployeeUuid(
                                            entity.getEmployeeId()
                                    )
                    )
                    .map(EmployeeCache::getManagerEmployeeId)
                    .filter(
                            mgrId ->
                                    mgrId != null
                                            && !mgrId.isBlank()
                    )
                    .ifPresent(entity::setManagerId);
        }
    }


    // ============================================================
    // COST CENTER OWNER
    // ============================================================

    private String resolveCostCenterOwner(
            CashAdvance advance) {

        if (advance == null
                || advance.getEmployeeId() == null) {

            return null;
        }

        String empId = advance.getEmployeeId();

        Optional<EmployeeCache> empOpt =
                employeeCacheRepository
                        .findByEmployeeId(empId)
                        .or(() ->
                                employeeCacheRepository
                                        .findByEmployeeUuid(empId)
                        );

        if (empOpt.isPresent()
                && empOpt.get().getDepartmentUuid() != null) {

            String deptUuidStr =
                    empOpt.get().getDepartmentUuid();

            try {

                UUID deptUuid =
                        UUID.fromString(deptUuidStr);

                List<CostCenter> costCenters =
                        costCenterRepository.findAll();

                for (CostCenter cc : costCenters) {

                    if (deptUuid.equals(cc.getDepartmentUuid())
                            && cc.getOwnerEmployeeId() != null
                            && !cc.getOwnerEmployeeId().isBlank()) {

                        return cc.getOwnerEmployeeId();
                    }
                }

            } catch (Exception e) {

                log.debug(
                        "Invalid departmentUuid for employee {}: {}",
                        empId,
                        deptUuidStr
                );
            }
        }

        return null;
    }


    // ============================================================
    // APPROVER AUTHORIZATION
    // ============================================================

    private void validateApproverAuthorization(
            CashAdvance entity,
            String approverId) {

        String actingUser =
                (approverId != null && !approverId.isBlank())
                        ? approverId
                        : currentUserService.getEmployeeId();

        if (actingUser != null
                && entity.getEmployeeId() != null
                && actingUser.trim().equalsIgnoreCase(
                        entity.getEmployeeId().trim()
                )) {

            throw new BusinessRuleViolationException(
                    "Employee cannot approve or reject their own cash advance request"
            );
        }

        String statusUpper =
                entity.getStatus() != null
                        ? entity.getStatus().toUpperCase()
                        : "";

        // Manager stage is reserved for the assigned manager (or delegate), even for Admin;
        // Admin keeps its override on the later stages.
        boolean managerStage = List.of(
                "SUBMITTED",
                "PENDING",
                "PENDING_APPROVAL",
                "PENDING_MANAGER_APPROVAL"
        ).contains(statusUpper);

        if (isAdminCaller() && !managerStage) {
            return;
        }

        if (List.of(
                "PENDING_FINANCE_APPROVAL",
                "PENDING_FINANCE"
        ).contains(statusUpper)) {

            if (isFinanceCaller()
                    || (
                        approverId != null
                        && (
                            approverId.toUpperCase().contains("FINANCE")
                            || approverId.toUpperCase().contains("ADMIN")
                        )
                    )) {

                return;
            }

            throw new BusinessRuleViolationException(
                    "Only Finance Executives can perform final approval or rejection at this stage"
            );
        }

        if (List.of(
                "PENDING_COST_CENTER_APPROVAL",
                "PENDING_COST_CENTER"
        ).contains(statusUpper)) {

            String ccOwner =
                    resolveCostCenterOwner(entity);

            Set<String> actingUserIds =
                    new HashSet<>();

            if (actingUser != null) {

                actingUserIds.add(actingUser);

                employeeCacheRepository
                        .findByEmployeeId(actingUser)
                        .map(EmployeeCache::getEmployeeUuid)
                        .ifPresent(actingUserIds::add);

                employeeCacheRepository
                        .findByEmployeeUuid(actingUser)
                        .map(EmployeeCache::getEmployeeId)
                        .ifPresent(actingUserIds::add);
            }

            if (ccOwner != null && !ccOwner.isBlank()) {

                for (String uid : actingUserIds) {

                    if (uid.equalsIgnoreCase(ccOwner)
                            || delegationService.canAct(
                                    uid,
                                    ccOwner
                            )) {

                        return;
                    }
                }
            }

            if (isFinanceCaller()) {
                return;
            }

            String assignedManager =
                    entity.getManagerId();

            if (assignedManager != null
                    && !assignedManager.isBlank()) {

                for (String uid : actingUserIds) {

                    if (delegationService.canAct(
                            uid,
                            assignedManager
                    )) {

                        return;
                    }
                }
            }

            throw new BusinessRuleViolationException(
                    "Only the Cost Center Owner (or active delegate/Finance) can approve or reject this cash advance"
            );
        }

        String assignedManager =
                entity.getManagerId();

        if (assignedManager == null
                || assignedManager.isBlank()) {

            assignedManager =
                    employeeCacheRepository
                            .findByEmployeeId(
                                    entity.getEmployeeId()
                            )
                            .or(() ->
                                    employeeCacheRepository
                                            .findByEmployeeUuid(
                                                    entity.getEmployeeId()
                                            )
                            )
                            .map(EmployeeCache::getManagerEmployeeId)
                            .orElse(null);

            if (assignedManager != null
                    && !assignedManager.isBlank()) {

                entity.setManagerId(assignedManager);

                cashAdvanceRepository.save(entity);
            }
        }

        Set<String> actingUserIds =
                new HashSet<>();

        if (actingUser != null) {

            actingUserIds.add(actingUser);

            employeeCacheRepository
                    .findByEmployeeId(actingUser)
                    .map(EmployeeCache::getEmployeeUuid)
                    .ifPresent(actingUserIds::add);

            employeeCacheRepository
                    .findByEmployeeUuid(actingUser)
                    .map(EmployeeCache::getEmployeeId)
                    .ifPresent(actingUserIds::add);
        }

        if (assignedManager != null
                && !assignedManager.isBlank()) {

            for (String uid : actingUserIds) {

                if (delegationService.canAct(
                        uid,
                        assignedManager
                )) {

                    return;
                }
            }
        }

        // Manager stage: only the assigned manager (or their active delegate) may act.
        // Finance approves later, once the manager stage is complete.
        throw new BusinessRuleViolationException(
                "Only the authorized manager (or active delegate) can approve or reject this cash advance"
        );
    }


    // ============================================================
    // FINANCE ROLE
    // ============================================================

    /**
     * A cash advance is visible to Finance and Admin, to the employee who requested it, and to the manager,
     * delegate or cost center owner who approves it. Anyone else gets "not found", so the response doesn't reveal
     * that the advance exists.
     */
    /**
     * The employee's other open advances, for the approver to see before deciding. Same approver check as approve:
     * only someone who can approve this request may read the employee's balance.
     */
    /** Approval view: this request, the employee's open advances (each at its ledger balance) and exposure against the limit. */
    @Override
    @Transactional(readOnly = true)
    public com.expense_management_service.dto.response.EmployeeAdvanceBalanceResponse getEmployeeBalanceForApproval(
            UUID advanceId, String approverId) {
        CashAdvance advance = findEntity(advanceId);
        validateApproverAuthorization(advance, approverId);

        List<com.expense_management_service.dto.response.EmployeeAdvanceBalanceResponse.OpenAdvance> others = new java.util.ArrayList<>();
        for (CashAdvance other : cashAdvanceRepository.findByEmployeeIdAndStatusIn(advance.getEmployeeId(), OPEN_BALANCE_STATUSES)) {
            if (other.getAdvanceId().equals(advanceId)) {
                continue;
            }
            BigDecimal owed = ledgerOutstanding(other);
            if (owed.signum() > 0) {
                others.add(new com.expense_management_service.dto.response.EmployeeAdvanceBalanceResponse.OpenAdvance(
                        other.getAdvanceId(), other.getTitle(), other.getStatus(), other.getAmount(), owed));
            }
        }
        BigDecimal outstanding = others.stream()
                .map(com.expense_management_service.dto.response.EmployeeAdvanceBalanceResponse.OpenAdvance::outstandingBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal committed = committedAmount(advance.getEmployeeId(), advanceId);

        CashAdvanceExposure.Result exposure = evaluateExposure(advance.getEmployeeId(), advanceId, outstanding, committed, advance.getAmount());

        return new com.expense_management_service.dto.response.EmployeeAdvanceBalanceResponse(
                advanceId,
                advance.getEmployeeId(),
                advance.getCurrency() != null ? advance.getCurrency().getCurrencyCode() : null,
                advance.getAmount(),
                outstanding,
                others,
                exposure.limit(),
                committed,
                exposure.potential(),
                exposure.excessOverLimit(),
                exposure.exceedsLimit(),
                managerDecisionOf(advance));
    }

    /** The manager's recorded recommendation, or null when there is none yet. */
    static com.expense_management_service.dto.response.EmployeeAdvanceBalanceResponse.ManagerDecision managerDecisionOf(CashAdvance advance) {
        boolean hasDecision = advance.getRecommendedTreatment() != null || advance.getManagerDecidedAt() != null;
        boolean hasException = advance.getExposureExceptionAt() != null;
        if (!hasDecision && !hasException) {
            return null;
        }
        UUID source = null;
        if (advance.getRecommendedSourceAdvanceId() != null && !advance.getRecommendedSourceAdvanceId().isBlank()) {
            source = UUID.fromString(advance.getRecommendedSourceAdvanceId());
        }
        return new com.expense_management_service.dto.response.EmployeeAdvanceBalanceResponse.ManagerDecision(
                advance.getRecommendedTreatment(),
                advance.getManagerJustification(),
                advance.getManagerDecidedBy(),
                advance.getManagerDecidedAt(),
                source,
                advance.getRecommendedSetOffAmount(),
                advance.getExposureExceptionJustification(),
                advance.getExposureExceptionBy(),
                advance.getExposureExceptionAt(),
                advance.getExposureExceptionAuthorizedBy() != null);
    }

    /** Statuses in which an advance is approved or pending but not disbursed: committed exposure. */
    private static final List<String> COMMITTED_STATUSES = List.of(
            "SUBMITTED", "PENDING_APPROVAL", "PENDING", "PENDING_MANAGER_APPROVAL",
            "PENDING_COST_CENTER_APPROVAL", "PENDING_COST_CENTER", "PENDING_FINANCE_APPROVAL", "PENDING_FINANCE", "APPROVED");

    /** Statuses in which the manager still has the decision. */
    private static final List<String> MANAGER_STAGE_STATUSES = List.of(
            "SUBMITTED", "PENDING_APPROVAL", "PENDING", "PENDING_MANAGER_APPROVAL");

    private static final String EXPOSURE_LIMIT_CONFIG_KEY = "cash_advance.max_employee_exposure";

    /** Outstanding balance of one advance, from its records: amount, active offsets and repayments. Never the stored field alone. */
    private BigDecimal ledgerOutstanding(CashAdvance advance) {
        if (balanceCalculator != null) {
            return balanceCalculator.outstanding(advance);
        }
        // Same rule as CashAdvanceBalanceCalculator.outstanding: active offsets and repayments only.
        BigDecimal offsets = CashAdvanceBalanceCalculator.activeOffsets(
                cashAdvanceAdjustmentRepository.findByCashAdvance_AdvanceId(advance.getAdvanceId()));
        BigDecimal repaid = CashAdvanceBalanceCalculator.repaymentTotal(
                cashAdvanceRepaymentRepository.findByCashAdvance_AdvanceId(advance.getAdvanceId()));
        int decimals = advance.getCurrency() != null ? advance.getCurrency().getDecimalPlaces() : 2;
        return CashAdvanceBalanceRules.requireNonNegative(CashAdvanceBalanceRules.ledgerBalance(advance, offsets, repaid), decimals);
    }

    /** Amount of the employee's other approved or pending advances that are not disbursed yet. */
    private BigDecimal committedAmount(String employeeId, UUID excludeAdvanceId) {
        return cashAdvanceRepository.findByEmployeeIdAndStatusIn(employeeId, COMMITTED_STATUSES).stream()
                .filter(a -> !a.getAdvanceId().equals(excludeAdvanceId))
                .map(a -> a.getApprovedAmount() != null ? a.getApprovedAmount() : a.getAmount())
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private CashAdvanceExposure.Result evaluateExposure(String employeeId, UUID advanceId, BigDecimal outstanding,
                                                        BigDecimal committed, BigDecimal requested) {
        return CashAdvanceExposure.evaluate(readExposureLimit(), outstanding, committed, requested);
    }

    /** The company limit on an employee's total exposure. Not configured means no limit. */
    private BigDecimal readExposureLimit() {
        return systemConfigurationRepository.findByConfigKey(EXPOSURE_LIMIT_CONFIG_KEY)
                .map(c -> CashAdvanceExposure.parseLimit(c.getConfigValue()))
                .orElse(null);
    }

    /**
     * The manager's decision. Each option is recorded on the advance before the normal approval or rejection runs, in the
     * same transaction, so a failed step leaves nothing half-written.
     */
    @Override
    public CashAdvanceResponse managerDecision(UUID advanceId, String approverId,
                                               com.expense_management_service.dto.request.CashAdvanceManagerDecisionRequest request) {
        if (request == null || request.decision() == null || request.decision().isBlank()) {
            throw new BusinessRuleViolationException("Choose a decision: APPROVE_FULL, APPROVE_REDUCED, SET_OFF, DEFER or REJECT.");
        }
        // Serialise with any other change to this advance.
        cashAdvanceRepository.findByIdForUpdate(advanceId);
        CashAdvance entity = findEntity(advanceId);
        validateApproverAuthorization(entity, approverId);
        if (entity.getRequestedAmount() == null) {
            entity.setRequestedAmount(entity.getAmount());
        }
        String decision = request.decision().trim().toUpperCase();
        String justification = request.justification() == null ? "" : request.justification().trim();
        String statusUpper = entity.getStatus() == null ? "" : entity.getStatus().toUpperCase();

        switch (decision) {
            case "APPROVE_FULL" -> {
                requireManagerStage(statusUpper);
                CashAdvanceExposure.Result exposure = exposureFor(entity);
                if (exposure.exceedsLimit()) {
                    requireText(justification, "The employee's exposure is "
                            + exposure.potential() + " against a limit of " + exposure.limit()
                            + ". Enter a justification for the exception.");
                    recordExposureException(entity, approverId, justification);
                }
                recordDecision(entity, "FULL_ADVANCE", approverId, justification);
                cashAdvanceRepository.save(entity);
                return approve(advanceId, approverId);
            }
            case "APPROVE_REDUCED" -> {
                requireManagerStage(statusUpper);
                BigDecimal reduced = request.approvedAmount();
                requireText(justification, "Explain why the amount is reduced.");
                if (reduced == null || reduced.signum() <= 0) {
                    throw new BusinessRuleViolationException("Enter the reduced amount to approve.");
                }
                if (entity.getAmount() != null && reduced.compareTo(entity.getAmount()) >= 0) {
                    throw new BusinessRuleViolationException("A reduced amount must be less than the requested " + entity.getAmount() + ".");
                }
                validateRoundAmount(reduced);
                CashAdvanceExposure.Result exposure = exposureFor(entity, reduced);
                if (exposure.exceedsLimit()) {
                    recordExposureException(entity, approverId, justification);
                }
                entity.setAmount(reduced);
                entity.setRecommendedAmount(reduced);
                recordDecision(entity, "REDUCED", approverId, justification);
                cashAdvanceRepository.save(entity);
                return approve(advanceId, approverId);
            }
            case "SET_OFF" -> {
                requireManagerStage(statusUpper);
                requireText(justification, "Explain the set-off recommendation for Finance.");
                UUID sourceId = request.setOffSourceAdvanceId();
                BigDecimal setOff = request.setOffAmount();
                if (sourceId == null || setOff == null || setOff.signum() <= 0) {
                    throw new BusinessRuleViolationException("Choose the earlier advance and the amount to set off.");
                }
                if (sourceId.equals(advanceId)) {
                    throw new BusinessRuleViolationException("An advance cannot be set off against itself.");
                }
                CashAdvance source = findEntity(sourceId);
                if (source.getEmployeeId() == null || !source.getEmployeeId().equals(entity.getEmployeeId())) {
                    throw new BusinessRuleViolationException("The set-off source must belong to the same employee.");
                }
                BigDecimal owed = ledgerOutstanding(source);
                if (setOff.compareTo(owed) > 0) {
                    throw new BusinessRuleViolationException("The set-off (" + setOff + ") is more than the source's outstanding balance (" + owed + ").");
                }
                if (entity.getAmount() != null && setOff.compareTo(entity.getAmount()) > 0) {
                    throw new BusinessRuleViolationException("The set-off cannot be more than the advance being approved.");
                }
                CashAdvanceExposure.Result exposure = exposureFor(entity);
                if (exposure.exceedsLimit()) {
                    recordExposureException(entity, approverId, justification);
                }
                entity.setRecommendedTreatment("SET_OFF");
                entity.setRecommendedSourceAdvanceId(sourceId.toString());
                entity.setRecommendedSetOffAmount(setOff);
                recordDecision(entity, "SET_OFF", approverId, justification);
                cashAdvanceRepository.save(entity);
                return approve(advanceId, approverId);
            }
            case "DEFER" -> {
                requireManagerStage(statusUpper);
                requireText(justification, "Give the reason for deferring this request.");
                entity.setStatus("DEFERRED");
                entity.setDeferredReason(justification);
                entity.setDeferredAt(LocalDateTime.now());
                recordDecision(entity, "DEFERRED", approverId, justification);
                CashAdvance saved = cashAdvanceRepository.save(entity);
                auditLogRepository.save(AuditLog.builder()
                        .entityName("CashAdvance").entityId(saved.getAdvanceId())
                        .action("CASH_ADVANCE_DEFERRED")
                        .oldValue(statusUpper).newValue("DEFERRED")
                        .performedBy(approverId).performedAt(LocalDateTime.now()).reason(justification)
                        .build());
                return cashAdvanceMapper.toResponse(saved);
            }
            case "REJECT" -> {
                requireText(justification, "A reason is required to reject.");
                return reject(advanceId, approverId, justification);
            }
            default -> throw new BusinessRuleViolationException("Unknown decision: " + request.decision());
        }
    }

    private void requireManagerStage(String statusUpper) {
        if (!MANAGER_STAGE_STATUSES.contains(statusUpper)) {
            throw new BusinessRuleViolationException("The manager decision applies only while the request is waiting for the manager. Current status: " + statusUpper);
        }
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessRuleViolationException(message);
        }
    }

    private CashAdvanceExposure.Result exposureFor(CashAdvance entity) {
        return exposureFor(entity, entity.getAmount());
    }

    private CashAdvanceExposure.Result exposureFor(CashAdvance entity, BigDecimal requested) {
        BigDecimal outstanding = BigDecimal.ZERO;
        for (CashAdvance other : cashAdvanceRepository.findByEmployeeIdAndStatusIn(entity.getEmployeeId(), OPEN_BALANCE_STATUSES)) {
            if (!other.getAdvanceId().equals(entity.getAdvanceId())) {
                outstanding = outstanding.add(ledgerOutstanding(other));
            }
        }
        return evaluateExposure(entity.getEmployeeId(), entity.getAdvanceId(), outstanding,
                committedAmount(entity.getEmployeeId(), entity.getAdvanceId()), requested);
    }

    private void recordExposureException(CashAdvance entity, String approverId, String justification) {
        entity.setExposureExceptionJustification(justification);
        entity.setExposureExceptionBy(approverId);
        entity.setExposureExceptionAt(LocalDateTime.now());
    }

    private void recordDecision(CashAdvance entity, String treatment, String approverId, String justification) {
        entity.setRecommendedTreatment(treatment);
        entity.setManagerJustification(justification);
        entity.setManagerDecidedBy(approverId);
        entity.setManagerDecidedAt(LocalDateTime.now());
    }

    /** Statuses in which an advance can still owe the employee or the company a balance. */
    private static final List<String> OPEN_BALANCE_STATUSES = List.of(
            "DISBURSED", "IN_PROGRESS", "EXPENSE_SUBMITTED", "EXPENSE_VERIFIED",
            "SETTLEMENT_PENDING", "PARTIALLY_ADJUSTED", "PARTIALLY_SETTLED", "AWAITING_REIMBURSEMENT");

    /** Open balances plus OVERDUE, so an overdue balance still counts toward what the employee owes. */
    private static final List<String> OUTSTANDING_DISPLAY_STATUSES =
            java.util.stream.Stream.concat(OPEN_BALANCE_STATUSES.stream(), java.util.stream.Stream.of("OVERDUE")).toList();

    /** What the employee owes and what a request of this type covers. Read from the ledger, never from the request. */
    private static final class Allocation {
        final BigDecimal openOutstanding;
        final BigDecimal eligibleOutstanding;
        final BigDecimal total;
        final boolean justificationRequired;

        Allocation(BigDecimal openOutstanding, BigDecimal eligibleOutstanding, BigDecimal total, boolean justificationRequired) {
            this.openOutstanding = openOutstanding;
            this.eligibleOutstanding = eligibleOutstanding;
            this.total = total;
            this.justificationRequired = justificationRequired;
        }

        BigDecimal openOutstanding() { return openOutstanding; }
        BigDecimal eligibleOutstanding() { return eligibleOutstanding; }
        BigDecimal total() { return total; }
        boolean justificationRequired() { return justificationRequired; }
    }

    private Allocation allocationFor(String employeeId, CashAdvanceRequestType type, BigDecimal amount) {
        BigDecimal open = BigDecimal.ZERO;
        BigDecimal eligible = BigDecimal.ZERO;
        for (CashAdvance advance : cashAdvanceRepository.findByEmployeeIdAndStatusIn(employeeId, OUTSTANDING_DISPLAY_STATUSES)) {
            BigDecimal owed = ledgerOutstanding(advance);
            if (owed.signum() <= 0) {
                continue;
            }
            open = open.add(owed);
            if (CashAdvanceAllocationRules.rolloverEligible(advance.getStatus())) {
                eligible = eligible.add(owed);
            }
        }
        if (type == CashAdvanceRequestType.ADVANCE_PLUS_OUTSTANDING && eligible.signum() <= 0) {
            throw new BusinessRuleViolationException(
                    "You have no outstanding balance that can be included. Choose Complete New Cash Advance instead.");
        }
        return new Allocation(open, eligible,
                CashAdvanceAllocationRules.totalAllocation(type, eligible, amount),
                CashAdvanceAllocationRules.justificationRequired(type, open));
    }

    /** Validates the request type and its amounts, then stores the calculation on the advance. Called on create and update. */
    void applyAllocationRules(CashAdvance entity, CashAdvanceRequest request) {
        CashAdvanceRequestType type = CashAdvanceAllocationRules.typeOrDefault(request.requestType());
        BigDecimal amount = request.amount();
        if (type == CashAdvanceRequestType.COMPLETE_NEW && request.additionalAmount() != null) {
            throw new BusinessRuleViolationException("An additional amount applies only to Cash Advance + Outstanding.");
        }
        if (type == CashAdvanceRequestType.ADVANCE_PLUS_OUTSTANDING
                && request.additionalAmount() != null && request.additionalAmount().compareTo(amount) != 0) {
            throw new BusinessRuleViolationException("The additional amount must match the amount required.");
        }
        Allocation allocation = allocationFor(entity.getEmployeeId(), type, amount);
        String justification = CashAdvanceAllocationRules.validJustification(
                allocation.justificationRequired(), request.employeeJustification());
        entity.setRequestType(type.name());
        entity.setAdditionalAmountRequested(type == CashAdvanceRequestType.ADVANCE_PLUS_OUTSTANDING ? amount : null);
        entity.setOutstandingAmountSnapshot(allocation.openOutstanding());
        entity.setTotalAdvanceAllocation(allocation.total());
        entity.setEmployeeJustification(justification);
    }

    @Override
    public CashAdvancePreviewResponse preview(CashAdvancePreviewRequest request) {
        validateRoundAmount(request.amount());
        String employeeId = currentUserService.getEmployeeId();
        CashAdvanceRequestType type = CashAdvanceAllocationRules.typeOrDefault(request.requestType());
        Allocation allocation = allocationFor(employeeId, type, request.amount());
        CashAdvanceExposure.Result exposure =
                evaluateExposure(employeeId, null, allocation.openOutstanding(), committedAmount(employeeId, null), request.amount());
        String currencyCode = request.currencyId() == null ? null : findCurrency(request.currencyId()).getCurrencyCode();
        return new CashAdvancePreviewResponse(
                type,
                currencyCode,
                allocation.openOutstanding(),
                type == CashAdvanceRequestType.ADVANCE_PLUS_OUTSTANDING ? allocation.eligibleOutstanding() : BigDecimal.ZERO,
                request.amount(),
                allocation.total(),
                allocation.justificationRequired(),
                exposure.limit(),
                exposure.potential(),
                exposure.exceedsLimit(),
                exposure.excessOverLimit());
    }

    @Override
    public EmployeeProfileResponse myProfile() {
        String employeeId = currentUserService.getEmployeeId();
        EmployeeCache me = employeeCacheRepository.findByEmployeeId(employeeId).orElse(null);
        String managerId = me == null ? null : me.getManagerEmployeeId();
        String managerName = managerId == null ? null
                : employeeCacheRepository.findByEmployeeId(managerId).map(m -> joinName(m.getFirstName(), m.getLastName())).orElse(null);
        return new EmployeeProfileResponse(
                employeeId,
                me == null ? null : joinName(me.getFirstName(), me.getLastName()),
                managerId,
                managerName,
                null,   // department name: the cache holds only an ID, so it is not shown until a name lookup exists
                null);  // designation name: same
    }

    private static String joinName(String first, String last) {
        String name = ((first == null ? "" : first.trim()) + " " + (last == null ? "" : last.trim())).trim();
        return name.isEmpty() ? null : name;
    }

    private void assertCanView(CashAdvance advance) {
        if (isFinanceCaller() || isAdminCaller()) {
            return;
        }
        String me = currentUserService.getEmployeeId();
        if (me == null || me.isBlank()) {
            throw notVisible(advance);
        }
        if (advance.getEmployeeId() != null && advance.getEmployeeId().trim().equalsIgnoreCase(me.trim())) {
            return;
        }
        Set<String> actingFor = new HashSet<>(delegationService.resolveApproverIdsActingFor(me));
        if (advance.getManagerId() != null && actingFor.contains(advance.getManagerId())) {
            return;
        }
        String ccOwner = resolveCostCenterOwner(advance);
        if (ccOwner != null && actingFor.contains(ccOwner)) {
            return;
        }
        throw notVisible(advance);
    }

    private ResourceNotFoundException notVisible(CashAdvance advance) {
        return new ResourceNotFoundException("Cash advance not found with id: " + advance.getAdvanceId());
    }

    private boolean isFinanceCaller() {

        try {

            var auth =
                    SecurityContextHolder
                            .getContext()
                            .getAuthentication();

            if (auth != null
                    && auth.getAuthorities() != null) {

                return auth.getAuthorities()
                        .stream()
                        .anyMatch(
                                a ->
                                        RoleConstants.ROLE_FINANCE
                                                .equals(a.getAuthority())
                                        || RoleConstants.FINANCE
                                                .equals(a.getAuthority())
                                        || RoleConstants.ROLE_FINANCE_EXECUTIVE
                                                .equals(a.getAuthority())
                                        || RoleConstants.FINANCE_EXECUTIVE
                                                .equals(a.getAuthority())
                        );
            }

        } catch (Exception e) {

            log.debug(
                    "Could not determine user authorities from security context",
                    e
            );
        }

        return false;
    }


    // ============================================================
    // ADMIN ROLE
    // ============================================================

    private boolean isAdminCaller() {

        try {

            var auth =
                    SecurityContextHolder
                            .getContext()
                            .getAuthentication();

            if (auth != null
                    && auth.getAuthorities() != null) {

                return auth.getAuthorities()
                        .stream()
                        .anyMatch(
                                a ->
                                        RoleConstants.ROLE_ADMIN
                                                .equals(a.getAuthority())
                                        || RoleConstants.ADMIN
                                                .equals(a.getAuthority())
                        );
            }

        } catch (Exception e) {

            log.debug(
                    "Could not determine user authorities from security context",
                    e
            );
        }

        return false;
    }


    // ============================================================
    // OVERDUE ADVANCES
    // ============================================================

    private void checkOverdueAdvances(
            String employeeId, UUID exemptAdvanceId) {

        if (employeeId == null
                || employeeId.isBlank()) {

            return;
        }

        List<CashAdvance> activeAdvances =
                cashAdvanceRepository
                        .findByEmployeeIdAndStatusIn(
                                employeeId,
                                List.of(
                                        "DISBURSED",
                                        "IN_PROGRESS",
                                        "RECONCILIATION_PENDING",
                                        "SUBMITTED_FOR_REVIEW",
                                        "UNDER_REVIEW",
                                        "SETTLEMENT_PENDING",
                                        "PARTIALLY_SETTLED",
                                        "PARTIALLY_ADJUSTED",
                                        "OVERDUE"
                                )
                        );

        for (CashAdvance advance : activeAdvances) {

            if (advance.getAdvanceId().equals(exemptAdvanceId)) {
                continue;
            }
            if ("OVERDUE".equalsIgnoreCase(
                    advance.getStatus()
            )
                    && balanceCalculator.outstanding(advance).compareTo(BigDecimal.ZERO) > 0) {

                throw new BusinessRuleViolationException(
                        "Cannot request or submit a new cash advance when you have overdue unsettled advances (Advance ID: "
                                + advance.getAdvanceId()
                                + ")"
                );
            }

            if (advance.getSettlementDueDate() != null
                    && advance.getSettlementDueDate()
                            .isBefore(LocalDate.now())) {

                if (balanceCalculator.outstanding(advance).compareTo(BigDecimal.ZERO) > 0) {

                    throw new BusinessRuleViolationException(
                            "Cannot request or submit a new cash advance when you have overdue unsettled advances (Advance ID: "
                                    + advance.getAdvanceId()
                                    + ")"
                    );
                }
            }
        }
    }


    // ============================================================
    // COST CENTER BUDGET CONSUMPTION
    // ============================================================

    /**
     * Consumes the cash advance amount from the selected Cost Center's
     * current fiscal-year available budget.
     *
     * budgetAmount is the original allocated budget and is NOT changed.
     * availableBudget is the value that is reduced.
     */
    private void consumeCostCenterBudget(CashAdvance advance) {

        if (advance == null) {
            throw new BusinessRuleViolationException(
                    "Cash advance cannot be finally approved because the request is missing."
            );
        }

        // Legacy records can reach final approval without a Cost Center. The status still advances;
        // there is nothing to allocate against.
        if (advance.getCostCenter() == null
                || advance.getCostCenter().getCostCenterId() == null) {

            log.warn("Cash advance {} reached final approval without a selected Cost Center - skipping budget allocation.",
                    advance.getAdvanceId());
            return;
        }

        // Principal + fee: locked budget row, validation, atomic update and the fee record
        // all happen in CashAdvanceBudgetAllocator, inside this approval transaction.
        cashAdvanceBudgetAllocator.allocateOnFinalApproval(advance, resolveCurrentFiscalYear());
    }

    /**
     * Indian financial year: April 1 to March 31.
     * Example: September 2026 -> 2026-2027.
     */
    private String resolveCurrentFiscalYear() {
        LocalDate today = LocalDate.now();

        int startYear =
                today.getMonthValue() >= 4
                        ? today.getYear()
                        : today.getYear() - 1;

        return startYear + "-" + (startYear + 1);
    }


    // ============================================================
    // CASH ADVANCE LIMIT / BUDGET CHECK
    // ============================================================

    private void checkPolicyLimit(
            BigDecimal amount) {

        if (amount == null) {

            throw new BusinessRuleViolationException(
                    "Cash advance amount is required."
            );
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {

            throw new BusinessRuleViolationException(
                    "Cash advance amount must be greater than zero."
            );
        }

        /*
         * The configuration is optional.
         *
         * If cash_advance.max_amount does not exist,
         * do NOT break the existing Cash Advance flow.
         */
        var configOptional =
                systemConfigurationRepository
                        .findByConfigKey(
                                MAX_ADVANCE_AMOUNT_CONFIG_KEY
                        );

        if (configOptional.isEmpty()) {

            log.warn(
                    "Cash advance maximum limit configuration '{}' was not found. "
                            + "Skipping maximum-limit validation.",
                    MAX_ADVANCE_AMOUNT_CONFIG_KEY
            );

            return;
        }

        var config =
                configOptional.get();

        String configuredValue =
                config.getConfigValue();

        if (configuredValue == null
                || configuredValue.isBlank()) {

            log.warn(
                    "Cash advance maximum limit configuration '{}' is empty. "
                            + "Skipping maximum-limit validation.",
                    MAX_ADVANCE_AMOUNT_CONFIG_KEY
            );

            return;
        }

        final BigDecimal maxAmount;

        try {

            maxAmount =
                    new BigDecimal(
                            configuredValue.trim()
                    );

        } catch (NumberFormatException e) {

            log.error(
                    "Invalid cash advance maximum limit configuration. "
                            + "Key={}, Value={}",
                    MAX_ADVANCE_AMOUNT_CONFIG_KEY,
                    configuredValue
            );

            throw new BusinessRuleViolationException(
                    "Cash advance maximum limit is configured incorrectly."
            );
        }

        if (maxAmount.compareTo(BigDecimal.ZERO) <= 0) {

            log.warn(
                    "Cash advance maximum limit is <= 0. "
                            + "Skipping maximum-limit validation."
            );

            return;
        }

        /*
         * Actual maximum-limit blocking.
         */
        if (amount.compareTo(maxAmount) > 0) {

            throw new BusinessRuleViolationException(
                    "Cash advance amount of "
                            + amount
                            + " exceeds the maximum allowed amount of "
                            + maxAmount
                            + "."
            );
        }
    }


    // ============================================================
    // CURRENCY
    // ============================================================

    private Currency findCurrency(
            UUID currencyId) {

        return currencyRepository
                .findById(currencyId)
                .orElseThrow(
                        () ->
                                new ResourceNotFoundException(
                                        "Currency not found with id: "
                                                + currencyId
                                )
                );
    }


    // ============================================================
    // FIND CASH ADVANCE
    // ============================================================

    private CashAdvance findEntity(
            UUID advanceId) {

        return cashAdvanceRepository
                .findById(advanceId)
                .orElseThrow(
                        () ->
                                new ResourceNotFoundException(
                                        "CashAdvance not found with id: "
                                                + advanceId
                                )
                );
    }
}