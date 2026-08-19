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
import com.expense_management_service.mapper.CostCenterBudgetMapper;
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

    @Override
    public CostCenterBudgetResponse create(CostCenterBudgetRequest request) {
        CostCenter costCenter = findActiveCostCenter(request.costCenterId());
        assertNoDuplicateFiscalYear(null, request.costCenterId(), request.fiscalYear());

        BigDecimal availableBudget = request.availableBudget() != null ? request.availableBudget() : request.budgetAmount();
        assertAvailableBudgetValid(availableBudget, request.budgetAmount());

        CostCenterBudget entity = costCenterBudgetMapper.toEntity(request);
        entity.setCostCenter(costCenter);
        entity.setAvailableBudget(availableBudget);

        CostCenterBudget saved = costCenterBudgetRepository.save(entity);
        log.info("Created cost center budget for {} fiscal year {}: budgetAmount={}, availableBudget={}",
                costCenter.getCostCenterCode(), request.fiscalYear(), request.budgetAmount(), availableBudget);
        return costCenterBudgetMapper.toResponse(saved);
    }

    @Override
    public CostCenterBudgetResponse update(UUID budgetId, CostCenterBudgetRequest request) {
        CostCenterBudget entity = findEntity(budgetId);
        CostCenter costCenter = findActiveCostCenter(request.costCenterId());
        assertNoDuplicateFiscalYear(budgetId, request.costCenterId(), request.fiscalYear());

        BigDecimal availableBudget = request.availableBudget() != null ? request.availableBudget() : entity.getAvailableBudget();
        assertAvailableBudgetValid(availableBudget, request.budgetAmount());

        costCenterBudgetMapper.updateEntity(entity, request);
        entity.setCostCenter(costCenter);
        entity.setAvailableBudget(availableBudget);

        CostCenterBudget saved = costCenterBudgetRepository.save(entity);
        log.info("Updated cost center budget {}", budgetId);
        return costCenterBudgetMapper.toResponse(saved);
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
