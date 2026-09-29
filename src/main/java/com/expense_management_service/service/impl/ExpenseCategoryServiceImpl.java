package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ExpenseCategoryRequest;
import com.expense_management_service.dto.response.ExpenseCategoryResponse;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.GlAccount;
import com.expense_management_service.entity.TaxCode;
import com.expense_management_service.mapper.ExpenseCategoryMapper;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.GlAccountRepository;
import com.expense_management_service.repository.TaxCodeRepository;
import com.expense_management_service.service.ExpenseCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class ExpenseCategoryServiceImpl implements ExpenseCategoryService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final GlAccountRepository glAccountRepository;
    private final TaxCodeRepository taxCodeRepository;
    private final ExpenseCategoryMapper expenseCategoryMapper;

    @Override
    public ExpenseCategoryResponse create(ExpenseCategoryRequest request) {
        assertNameNotDuplicate(request.categoryName(), null);
        assertEffectiveDatesValid(request);

        ExpenseCategory entity = expenseCategoryMapper.toEntity(request);
        entity.setTaxCode(resolveTaxCode(request.taxCode(), null));
        entity.setGlAccount(findActiveGlAccount(request.glAccountId()));
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(STATUS_ACTIVE);
        }

        return toResponse(expenseCategoryRepository.save(entity));
    }

    @Override
    public ExpenseCategoryResponse update(UUID categoryId, ExpenseCategoryRequest request) {
        ExpenseCategory entity = findEntity(categoryId);
        assertNameNotDuplicate(request.categoryName(), categoryId);
        assertEffectiveDatesValid(request);

        String previousTaxCode = entity.getTaxCode();
        expenseCategoryMapper.updateEntity(entity, request);
        entity.setTaxCode(resolveTaxCode(request.taxCode(), previousTaxCode));
        entity.setGlAccount(findActiveGlAccount(request.glAccountId()));
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(STATUS_ACTIVE);
        }

        return toResponse(expenseCategoryRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public ExpenseCategoryResponse getById(UUID categoryId) {
        return toResponse(findEntity(categoryId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpenseCategoryResponse> getAll() {
        Map<String, BigDecimal> rates = applicableTaxRates();
        return expenseCategoryRepository.findAll().stream()
                .map(category -> expenseCategoryMapper.toResponse(category, rateFor(category, rates)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpenseCategoryResponse> getActiveCategories() {
        Map<String, BigDecimal> rates = applicableTaxRates();
        return expenseCategoryRepository.findByStatusIgnoreCaseOrderByCategoryNameAsc(STATUS_ACTIVE).stream()
                .map(category -> expenseCategoryMapper.toResponse(category, rateFor(category, rates)))
                .toList();
    }

    @Override
    public void delete(UUID categoryId) {
        expenseCategoryRepository.delete(findEntity(categoryId));
    }

    private void assertNameNotDuplicate(String categoryName, UUID currentCategoryId) {
        expenseCategoryRepository.findByCategoryNameIgnoreCase(categoryName).ifPresent(existing -> {
            if (!existing.getCategoryId().equals(currentCategoryId)) {
                throw new DuplicateResourceException("Expense category name already exists: " + categoryName);
            }
        });
    }

    private void assertEffectiveDatesValid(ExpenseCategoryRequest request) {
        if (request.effectiveTo() != null && request.effectiveTo().isBefore(request.effectiveFrom())) {
            throw new IllegalArgumentException("effectiveTo cannot be before effectiveFrom");
        }
    }

    private GlAccount findActiveGlAccount(UUID glAccountId) {
        GlAccount glAccount = glAccountRepository.findById(glAccountId)
                .orElseThrow(() -> new ResourceNotFoundException("GlAccount not found with id: " + glAccountId));
        if (!STATUS_ACTIVE.equalsIgnoreCase(glAccount.getStatus())) {
            throw new IllegalArgumentException(
                    "GL Account " + glAccount.getGlAccountCode() + " is not Active and cannot be mapped to a category");
        }
        return glAccount;
    }

    /**
     * Tax code is optional; when given it must exist in the Tax Configuration master and be ACTIVE.
     * Returns the master's canonical spelling. An unchanged legacy value (free text saved before the
     * master existed) is kept as-is, so editing some other field never forces a remap.
     */
    private String resolveTaxCode(String requested, String previous) {
        if (!StringUtils.hasText(requested)) {
            return null;
        }
        String trimmed = requested.trim();
        if (previous != null && previous.equalsIgnoreCase(trimmed)
                && taxCodeRepository.findByTaxCodeIgnoreCase(trimmed).isEmpty()) {
            return previous;
        }
        TaxCode taxCode = taxCodeRepository.findByTaxCodeIgnoreCase(trimmed)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Tax code " + trimmed + " does not exist in Tax Configuration"));
        if (!STATUS_ACTIVE.equalsIgnoreCase(taxCode.getStatus())) {
            throw new IllegalArgumentException(
                    "Tax code " + taxCode.getTaxCode() + " is not Active and cannot be mapped to a category");
        }
        return taxCode.getTaxCode();
    }

    private ExpenseCategoryResponse toResponse(ExpenseCategory category) {
        return expenseCategoryMapper.toResponse(category, rateFor(category, applicableTaxRates()));
    }

    /** Upper-cased code -> rate, for every tax code active and in effect today. */
    private Map<String, BigDecimal> applicableTaxRates() {
        LocalDate today = LocalDate.now();
        return taxCodeRepository.findByStatusIgnoreCaseOrderByRatePercentAsc(STATUS_ACTIVE).stream()
                .filter(taxCode -> taxCode.isApplicableOn(today))
                .collect(Collectors.toMap(taxCode -> taxCode.getTaxCode().toUpperCase(), TaxCode::getRatePercent, (a, b) -> a));
    }

    private BigDecimal rateFor(ExpenseCategory category, Map<String, BigDecimal> rates) {
        return category.getTaxCode() == null ? null : rates.get(category.getTaxCode().toUpperCase());
    }

    private ExpenseCategory findEntity(UUID categoryId) {
        return expenseCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseCategory not found with id: " + categoryId));
    }
}
