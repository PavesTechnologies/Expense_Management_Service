package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceInUseException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.TaxCodeRequest;
import com.expense_management_service.dto.response.TaxCodeResponse;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.GlAccount;
import com.expense_management_service.entity.TaxCode;
import com.expense_management_service.enums.TaxType;
import com.expense_management_service.mapper.TaxCodeMapper;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.GlAccountRepository;
import com.expense_management_service.repository.TaxCodeRepository;
import com.expense_management_service.service.TaxCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class TaxCodeServiceImpl implements TaxCodeService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final TaxCodeRepository taxCodeRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final GlAccountRepository glAccountRepository;
    private final TaxCodeMapper taxCodeMapper;

    @Override
    public TaxCodeResponse create(TaxCodeRequest request) {
        assertValid(request);
        assertCodeNotDuplicate(request.taxCode(), null);

        TaxCode entity = taxCodeMapper.toEntity(request);
        entity.setInputTaxGlAccount(resolveGlAccount(request.inputTaxGlAccountId()));
        defaultStatus(entity);

        TaxCode saved = taxCodeRepository.save(entity);
        return taxCodeMapper.toResponse(saved, 0L);
    }

    @Override
    public TaxCodeResponse update(UUID taxCodeId, TaxCodeRequest request) {
        TaxCode entity = findEntity(taxCodeId);
        assertValid(request);
        assertCodeNotDuplicate(request.taxCode(), taxCodeId);

        String previousCode = entity.getTaxCode();
        taxCodeMapper.updateEntity(entity, request);
        entity.setInputTaxGlAccount(resolveGlAccount(request.inputTaxGlAccountId()));
        defaultStatus(entity);
        TaxCode saved = taxCodeRepository.save(entity);

        // Categories reference the code by value, so a rename must carry them along.
        if (!previousCode.equalsIgnoreCase(saved.getTaxCode())) {
            List<ExpenseCategory> mapped = expenseCategoryRepository.findByTaxCodeIgnoreCase(previousCode);
            mapped.forEach(category -> category.setTaxCode(saved.getTaxCode()));
            expenseCategoryRepository.saveAll(mapped);
        }

        return taxCodeMapper.toResponse(saved, countMappedCategories(saved.getTaxCode()));
    }

    @Override
    @Transactional(readOnly = true)
    public TaxCodeResponse getById(UUID taxCodeId) {
        TaxCode entity = findEntity(taxCodeId);
        return taxCodeMapper.toResponse(entity, countMappedCategories(entity.getTaxCode()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxCodeResponse> getAll() {
        return taxCodeRepository.findAllByOrderByTaxCodeAsc().stream()
                .map(entity -> taxCodeMapper.toResponse(entity, countMappedCategories(entity.getTaxCode())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxCodeResponse> getActive() {
        return taxCodeRepository.findByStatusIgnoreCaseOrderByRatePercentAsc(STATUS_ACTIVE).stream()
                .map(entity -> taxCodeMapper.toResponse(entity, countMappedCategories(entity.getTaxCode())))
                .toList();
    }

    @Override
    public void delete(UUID taxCodeId) {
        TaxCode entity = findEntity(taxCodeId);

        List<ExpenseCategory> mappedCategories = expenseCategoryRepository.findByTaxCodeIgnoreCase(entity.getTaxCode());
        if (!mappedCategories.isEmpty()) {
            String categoryNames = mappedCategories.stream()
                    .map(ExpenseCategory::getCategoryName)
                    .collect(Collectors.joining(", "));
            throw new ResourceInUseException(
                    "Tax code cannot be deleted because it is mapped to the following Expense Categories: " + categoryNames
                            + ". Deactivate it instead, or remap those categories first.");
        }

        taxCodeRepository.delete(entity);
    }

    private void assertValid(TaxCodeRequest request) {
        if (request.effectiveTo() != null && request.effectiveTo().isBefore(request.effectiveFrom())) {
            throw new IllegalArgumentException("effectiveTo cannot be before effectiveFrom");
        }
        if (request.taxType() == TaxType.EXEMPT && request.ratePercent().compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalArgumentException("An EXEMPT tax code must have a rate of 0");
        }
    }

    private void assertCodeNotDuplicate(String taxCode, UUID currentTaxCodeId) {
        taxCodeRepository.findByTaxCodeIgnoreCase(taxCode.trim()).ifPresent(existing -> {
            if (!existing.getTaxCodeId().equals(currentTaxCodeId)) {
                throw new DuplicateResourceException("Tax code already exists: " + taxCode);
            }
        });
    }

    private GlAccount resolveGlAccount(UUID glAccountId) {
        if (glAccountId == null) {
            return null;
        }
        GlAccount glAccount = glAccountRepository.findById(glAccountId)
                .orElseThrow(() -> new ResourceNotFoundException("GlAccount not found with id: " + glAccountId));
        if (!STATUS_ACTIVE.equalsIgnoreCase(glAccount.getStatus())) {
            throw new IllegalArgumentException(
                    "GL Account " + glAccount.getGlAccountCode() + " is not Active and cannot be used for input tax");
        }
        return glAccount;
    }

    private void defaultStatus(TaxCode entity) {
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(STATUS_ACTIVE);
        }
    }

    private long countMappedCategories(String taxCode) {
        return expenseCategoryRepository.countByTaxCodeIgnoreCase(taxCode);
    }

    private TaxCode findEntity(UUID taxCodeId) {
        return taxCodeRepository.findById(taxCodeId)
                .orElseThrow(() -> new ResourceNotFoundException("TaxCode not found with id: " + taxCodeId));
    }
}
