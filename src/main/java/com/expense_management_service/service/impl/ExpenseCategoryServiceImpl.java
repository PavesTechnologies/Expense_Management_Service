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
import com.expense_management_service.service.ExpenseCategoryTaxMappingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ExpenseCategoryServiceImpl implements ExpenseCategoryService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final GlAccountRepository glAccountRepository;
    private final TaxCodeRepository taxCodeRepository;
    private final ExpenseCategoryMapper expenseCategoryMapper;
    private final ExpenseCategoryTaxMappingService taxMappingService;

    @Override
    public ExpenseCategoryResponse create(ExpenseCategoryRequest request) {
        assertNameNotDuplicate(request.categoryName(), null);
        assertEffectiveDatesValid(request);

        ExpenseCategory entity = expenseCategoryMapper.toEntity(request);
        Optional<TaxCode> taxCode = resolveTaxCode(request.taxCode());
        entity.setTaxCode(taxCode.map(TaxCode::getTaxCode).orElse(null));
        entity.setGlAccount(findActiveGlAccount(request.glAccountId()));
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(STATUS_ACTIVE);
        }

        ExpenseCategory saved = expenseCategoryRepository.save(entity);
        // Mapped from when the category (or, if later, the code) takes effect, so backdated expenses resolve it.
        taxCode.ifPresent(code -> taxMappingService.replaceCurrentMapping(saved, code,
                latest(saved.getEffectiveFrom(), code.getEffectiveFrom())));
        return toResponse(saved);
    }

    @Override
    public ExpenseCategoryResponse update(UUID categoryId, ExpenseCategoryRequest request) {
        ExpenseCategory entity = findEntity(categoryId);
        assertNameNotDuplicate(request.categoryName(), categoryId);
        assertEffectiveDatesValid(request);

        String previousTaxCode = entity.getTaxCode();
        // An unchanged value is never re-resolved - whether it is legacy free text or a code that has
        // since been deactivated - so editing some other field on the category never forces a remap.
        boolean taxCodeChanged = !sameCode(previousTaxCode, request.taxCode());
        Optional<TaxCode> taxCode = taxCodeChanged ? resolveTaxCode(request.taxCode()) : Optional.empty();
        expenseCategoryMapper.updateEntity(entity, request);
        entity.setTaxCode(taxCodeChanged ? taxCode.map(TaxCode::getTaxCode).orElse(null) : previousTaxCode);
        entity.setGlAccount(findActiveGlAccount(request.glAccountId()));
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(STATUS_ACTIVE);
        }

        ExpenseCategory saved = expenseCategoryRepository.save(entity);
        // The single tax code field is the compatibility path onto the dated mapping: a change applies from today.
        if (taxCodeChanged) {
            taxMappingService.replaceCurrentMapping(saved, taxCode.orElse(null), LocalDate.now());
        }
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ExpenseCategoryResponse getById(UUID categoryId) {
        return toResponse(findEntity(categoryId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpenseCategoryResponse> getAll() {
        Map<UUID, TaxCode> rates = applicableTaxRates();
        return expenseCategoryRepository.findAll().stream()
                .map(category -> expenseCategoryMapper.toResponse(category, rateFor(category, rates)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpenseCategoryResponse> getActiveCategories() {
        Map<UUID, TaxCode> rates = applicableTaxRates();
        return expenseCategoryRepository.findByStatusIgnoreCaseOrderByCategoryNameAsc(STATUS_ACTIVE).stream()
                .map(category -> expenseCategoryMapper.toResponse(category, rateFor(category, rates)))
                .toList();
    }

    @Override
    public void delete(UUID categoryId) {
        ExpenseCategory entity = findEntity(categoryId);
        taxMappingService.deleteAllForCategory(categoryId);
        expenseCategoryRepository.delete(entity);
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

    /** Blank and null are the same "no tax code"; otherwise compared ignoring case and padding. */
    private static boolean sameCode(String previous, String requested) {
        String a = StringUtils.hasText(previous) ? previous.trim() : null;
        String b = StringUtils.hasText(requested) ? requested.trim() : null;
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    private static LocalDate latest(LocalDate a, LocalDate b) {
        return a == null ? b : (b == null || a.isAfter(b) ? a : b);
    }

    /** Tax code is optional; a newly chosen code must exist in the Tax Configuration master and be ACTIVE. */
    private Optional<TaxCode> resolveTaxCode(String requested) {
        if (!StringUtils.hasText(requested)) {
            return Optional.empty();
        }
        String trimmed = requested.trim();
        TaxCode taxCode = taxCodeRepository.findByTaxCodeIgnoreCase(trimmed)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Tax code " + trimmed + " does not exist in Tax Configuration"));
        if (!STATUS_ACTIVE.equalsIgnoreCase(taxCode.getStatus())) {
            throw new IllegalArgumentException(
                    "Tax code " + taxCode.getTaxCode() + " is not Active and cannot be mapped to a category");
        }
        return Optional.of(taxCode);
    }

    private ExpenseCategoryResponse toResponse(ExpenseCategory category) {
        return expenseCategoryMapper.toResponse(category, rateFor(category, applicableTaxRates()));
    }

    /** Category id -> code mapped today, in one query. */
    private Map<UUID, TaxCode> applicableTaxRates() {
        return taxMappingService.mappedCodesOn(LocalDate.now());
    }

    /** Rate of the code mapped today, if that code is active and in effect today - else null (no GST pre-fill). */
    private BigDecimal rateFor(ExpenseCategory category, Map<UUID, TaxCode> mappedToday) {
        TaxCode code = mappedToday.get(category.getCategoryId());
        return code != null && code.isApplicableOn(LocalDate.now()) ? code.getRatePercent() : null;
    }

    private ExpenseCategory findEntity(UUID categoryId) {
        return expenseCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseCategory not found with id: " + categoryId));
    }
}
