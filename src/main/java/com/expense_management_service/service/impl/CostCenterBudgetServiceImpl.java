package com.expense_management_service.service.impl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.CostCenterBudgetRequest;
import com.expense_management_service.dto.response.CostCenterBudgetResponse;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.CostCenterBudget;
import com.expense_management_service.enums.BudgetEncumbranceStatus;
import com.expense_management_service.mapper.CostCenterBudgetMapper;
import com.expense_management_service.repository.BudgetEncumbranceRepository;
import com.expense_management_service.repository.CostCenterBudgetRepository;
import com.expense_management_service.repository.CostCenterRepository;
import com.expense_management_service.service.CostCenterBudgetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class CostCenterBudgetServiceImpl implements CostCenterBudgetService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final CostCenterBudgetRepository costCenterBudgetRepository;
    private final CostCenterRepository costCenterRepository;
    private final CostCenterBudgetMapper costCenterBudgetMapper;
    /**
     * Read-only dependency on the sibling repository, deliberately NOT on {@code
     * BudgetEncumbranceService} itself - that service depends on {@code CostCenterBudgetService}
     * (to call the existing, unmodified {@code consumeBudget} at AP completion), so depending on it
     * here in the other direction would create a circular bean dependency between the two services.
     * The "true unencumbered remainder" figure this class needs for rollover validation is a simple
     * repository-level aggregate, not business logic that belongs behind the sibling service.
     */
    private final BudgetEncumbranceRepository budgetEncumbranceRepository;

    @Override
    public CostCenterBudgetResponse create(CostCenterBudgetRequest request) {
        CostCenter costCenter = findActiveCostCenter(request.costCenterId());
        assertNoDuplicateFiscalYear(null, request.costCenterId(), request.fiscalYear());

        BigDecimal rolloverFromPrevious = assertRolloverValidAndReturn(request);
        BigDecimal totalPool = request.budgetAmount().add(rolloverFromPrevious);
        BigDecimal availableBudget = request.availableBudget() != null ? request.availableBudget() : totalPool;
        assertAvailableBudgetValid(availableBudget, totalPool);

        CostCenterBudget entity = costCenterBudgetMapper.toEntity(request);
        entity.setCostCenter(costCenter);
        entity.setRolloverFromPrevious(rolloverFromPrevious.signum() > 0 ? rolloverFromPrevious : null);
        entity.setAvailableBudget(availableBudget);

        CostCenterBudget saved = costCenterBudgetRepository.save(entity);
        log.info("Created cost center budget for {} fiscal year {}: budgetAmount={}, rolloverFromPrevious={}, availableBudget={}",
                costCenter.getCostCenterCode(), request.fiscalYear(), request.budgetAmount(), rolloverFromPrevious, availableBudget);
        return costCenterBudgetMapper.toResponse(saved);
    }

    @Override
    public CostCenterBudgetResponse update(UUID budgetId, CostCenterBudgetRequest request) {
        CostCenterBudget entity = findEntity(budgetId);
        CostCenter costCenter = findActiveCostCenter(request.costCenterId());
        assertNoDuplicateFiscalYear(budgetId, request.costCenterId(), request.fiscalYear());

        BigDecimal rolloverFromPrevious = assertRolloverValidAndReturn(request);
        BigDecimal totalPool = request.budgetAmount().add(rolloverFromPrevious);
        BigDecimal availableBudget = request.availableBudget() != null ? request.availableBudget() : entity.getAvailableBudget();
        assertAvailableBudgetValid(availableBudget, totalPool);

        costCenterBudgetMapper.updateEntity(entity, request);
        entity.setCostCenter(costCenter);
        entity.setRolloverFromPrevious(rolloverFromPrevious.signum() > 0 ? rolloverFromPrevious : null);
        entity.setAvailableBudget(availableBudget);

        CostCenterBudget saved = costCenterBudgetRepository.save(entity);
        log.info("Updated cost center budget {}", budgetId);
        return costCenterBudgetMapper.toResponse(saved);
    }

    /**
     * Group 2 Lock: {@code rolloverFromPrevious} must not exceed {@code MIN(source budget's true
     * unencumbered remainder, rolloverCap)}. A null/zero {@code rolloverFromPrevious} skips
     * validation entirely (nothing to check) and returns {@code BigDecimal.ZERO} so callers can add
     * it to {@code budgetAmount} unconditionally.
     */
    private BigDecimal assertRolloverValidAndReturn(CostCenterBudgetRequest request) {
        BigDecimal rolloverFromPrevious = request.rolloverFromPrevious();
        if (rolloverFromPrevious == null || rolloverFromPrevious.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (request.rolloverSourceBudgetId() == null) {
            throw new IllegalArgumentException("rolloverSourceBudgetId is required when rolloverFromPrevious is greater than zero");
        }

        CostCenterBudget source = costCenterBudgetRepository.findById(request.rolloverSourceBudgetId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Rollover source CostCenterBudget not found with id: " + request.rolloverSourceBudgetId()));

        BigDecimal sourceActiveEncumbered = budgetEncumbranceRepository
                .sumAmountByBudget_BudgetIdAndStatus(source.getBudgetId(), BudgetEncumbranceStatus.ACTIVE);
        BigDecimal trueUnencumberedRemainder = source.getAvailableBudget().subtract(sourceActiveEncumbered);

        BigDecimal ceiling = request.rolloverCap() != null
                ? trueUnencumberedRemainder.min(request.rolloverCap())
                : trueUnencumberedRemainder;

        if (rolloverFromPrevious.compareTo(ceiling) > 0) {
            throw new IllegalArgumentException("rolloverFromPrevious (" + rolloverFromPrevious + ") cannot exceed the source "
                    + "budget's true unencumbered remainder capped at the rollover cap (" + ceiling + ")");
        }
        return rolloverFromPrevious;
    }

    @Override
    @Transactional(readOnly = true)
    public CostCenterBudgetResponse getById(UUID budgetId) {
        return costCenterBudgetMapper.toResponse(findEntity(budgetId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CostCenterBudgetResponse> getAll() {
        return costCenterBudgetRepository.findAll().stream().map(costCenterBudgetMapper::toResponse).toList();
    }

    @Override
    public void delete(UUID budgetId) {
        CostCenterBudget entity = findEntity(budgetId);
        costCenterBudgetRepository.delete(entity);
        log.info("Deleted cost center budget {}", budgetId);
    }

    @Override
    public void consumeBudget(CostCenter costCenter, String fiscalYear, BigDecimal amount) {
        if (costCenter == null || fiscalYear == null || amount == null || amount.signum() == 0) {
            return;
        }
        Optional<CostCenterBudget> budgetOpt = costCenterBudgetRepository
                .findByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenter.getCostCenterId(), fiscalYear);
        if (budgetOpt.isEmpty()) {
            log.warn("No CostCenterBudget configured for cost center {} fiscal year {} - skipping budget consumption of {}",
                    costCenter.getCostCenterCode(), fiscalYear, amount);
            return;
        }

        CostCenterBudget budget = budgetOpt.get();
        BigDecimal newAvailable = budget.getAvailableBudget().subtract(amount);
        budget.setAvailableBudget(newAvailable);
        costCenterBudgetRepository.save(budget);

        if (newAvailable.signum() < 0) {
            log.warn("Cost center {} fiscal year {} available budget is now negative ({}) after consuming {} - "
                            + "no blocking rule exists for exceeding budget today",
                    costCenter.getCostCenterCode(), fiscalYear, newAvailable, amount);
        }
        log.info("Consumed {} from cost center {} fiscal year {} budget - available budget now {}",
                amount, costCenter.getCostCenterCode(), fiscalYear, newAvailable);
    }

    private CostCenter findActiveCostCenter(UUID costCenterId) {
        CostCenter costCenter = costCenterRepository.findById(costCenterId)
                .orElseThrow(() -> new ResourceNotFoundException("CostCenter not found with id: " + costCenterId));
        if (!STATUS_ACTIVE.equalsIgnoreCase(costCenter.getStatus())) {
            throw new IllegalArgumentException(
                    "Cost center " + costCenter.getCostCenterCode() + " is not Active and cannot have a budget assigned");
        }
        return costCenter;
    }

    private void assertAvailableBudgetValid(BigDecimal availableBudget, BigDecimal budgetAmount) {
        if (availableBudget.signum() < 0) {
            throw new IllegalArgumentException("Available budget cannot be negative");
        }
        if (availableBudget.compareTo(budgetAmount) > 0) {
            throw new IllegalArgumentException("Available budget cannot exceed budget amount");
        }
    }

    private void assertNoDuplicateFiscalYear(UUID currentBudgetId, UUID costCenterId, String fiscalYear) {
        costCenterBudgetRepository.findByCostCenter_CostCenterIdAndFiscalYearIgnoreCase(costCenterId, fiscalYear)
                .ifPresent(existing -> {
                    if (!existing.getBudgetId().equals(currentBudgetId)) {
                        throw new DuplicateResourceException(
                                "A budget for fiscal year " + fiscalYear + " already exists for this cost center");
                    }
                });
    }

    private CostCenterBudget findEntity(UUID budgetId) {
        return costCenterBudgetRepository.findById(budgetId)
                .orElseThrow(() -> new ResourceNotFoundException("CostCenterBudget not found with id: " + budgetId));
    }
}
