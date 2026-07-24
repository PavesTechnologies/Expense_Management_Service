package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ExpenseCategoryRequest;
import com.expense_management_service.dto.response.ExpenseCategoryResponse;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.GlAccount;
import com.expense_management_service.mapper.ExpenseCategoryMapper;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.GlAccountRepository;
import com.expense_management_service.service.ExpenseCategoryService;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ExpenseCategoryServiceImpl implements ExpenseCategoryService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final GlAccountRepository glAccountRepository;
    private final ExpenseCategoryMapper expenseCategoryMapper;

    @Override
    public ExpenseCategoryResponse create(ExpenseCategoryRequest request) {
        assertNameNotDuplicate(request.categoryName(), null);
        assertEffectiveDatesValid(request);

        ExpenseCategory entity = expenseCategoryMapper.toEntity(request);
        entity.setGlAccount(findActiveGlAccount(request.glAccountId()));
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(STATUS_ACTIVE);
        }

        return expenseCategoryMapper.toResponse(expenseCategoryRepository.save(entity));
    }

    @Override
    public ExpenseCategoryResponse update(UUID categoryId, ExpenseCategoryRequest request) {
        ExpenseCategory entity = findEntity(categoryId);
        assertNameNotDuplicate(request.categoryName(), categoryId);
        assertEffectiveDatesValid(request);

        expenseCategoryMapper.updateEntity(entity, request);
        entity.setGlAccount(findActiveGlAccount(request.glAccountId()));
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus(STATUS_ACTIVE);
        }

        return expenseCategoryMapper.toResponse(expenseCategoryRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public ExpenseCategoryResponse getById(UUID categoryId) {
        return expenseCategoryMapper.toResponse(findEntity(categoryId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpenseCategoryResponse> getAll() {
        return expenseCategoryRepository.findAll().stream().map(expenseCategoryMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpenseCategoryResponse> getActiveCategories() {
        return expenseCategoryRepository.findByStatusIgnoreCaseOrderByCategoryNameAsc(STATUS_ACTIVE).stream()
                .map(expenseCategoryMapper::toResponse)
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

    private ExpenseCategory findEntity(UUID categoryId) {
        return expenseCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseCategory not found with id: " + categoryId));
    }
}
