package com.expense_management_service.service.impl;

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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ExpenseCategoryServiceImpl implements ExpenseCategoryService {

    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final GlAccountRepository glAccountRepository;
    private final ExpenseCategoryMapper expenseCategoryMapper;

    @Override
    public ExpenseCategoryResponse create(ExpenseCategoryRequest request) {
        ExpenseCategory entity = expenseCategoryMapper.toEntity(request);
        entity.setGlAccount(findGlAccount(request.glAccountId()));
        return expenseCategoryMapper.toResponse(expenseCategoryRepository.save(entity));
    }

    @Override
    public ExpenseCategoryResponse update(UUID categoryId, ExpenseCategoryRequest request) {
        ExpenseCategory entity = findEntity(categoryId);
        expenseCategoryMapper.updateEntity(entity, request);
        entity.setGlAccount(findGlAccount(request.glAccountId()));
        return expenseCategoryMapper.toResponse(expenseCategoryRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public ExpenseCategoryResponse getById(UUID categoryId) {
        return expenseCategoryMapper.toResponse(findEntity(categoryId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ExpenseCategoryResponse> getAll(Pageable pageable) {
        return expenseCategoryRepository.findAll(pageable).map(expenseCategoryMapper::toResponse);
    }

    @Override
    public void delete(UUID categoryId) {
        expenseCategoryRepository.delete(findEntity(categoryId));
    }

    private GlAccount findGlAccount(UUID glAccountId) {
        return glAccountRepository.findById(glAccountId)
                .orElseThrow(() -> new ResourceNotFoundException("GlAccount not found with id: " + glAccountId));
    }

    private ExpenseCategory findEntity(UUID categoryId) {
        return expenseCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseCategory not found with id: " + categoryId));
    }
}
