package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ExpenseLineItemRequest;
import com.expense_management_service.dto.response.ExpenseLineItemResponse;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.entity.ProjectCache;
import com.expense_management_service.mapper.ExpenseLineItemMapper;
import com.expense_management_service.repository.CostCenterRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.ProjectCacheRepository;
import com.expense_management_service.service.ExpenseLineItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class ExpenseLineItemServiceImpl implements ExpenseLineItemService {

    private final ExpenseLineItemRepository expenseLineItemRepository;
    private final ExpenseReportRepository expenseReportRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final CurrencyRepository currencyRepository;
    private final CostCenterRepository costCenterRepository;
    private final ProjectCacheRepository projectCacheRepository;
    private final ExpenseLineItemMapper expenseLineItemMapper;

    @Override
    public ExpenseLineItemResponse create(ExpenseLineItemRequest request) {
        ExpenseLineItem entity = expenseLineItemMapper.toEntity(request);
        applyRelations(entity, request);
        return expenseLineItemMapper.toResponse(expenseLineItemRepository.save(entity));
    }

    @Override
    public ExpenseLineItemResponse update(UUID lineItemId, ExpenseLineItemRequest request) {
        ExpenseLineItem entity = findEntity(lineItemId);
        expenseLineItemMapper.updateEntity(entity, request);
        applyRelations(entity, request);
        return expenseLineItemMapper.toResponse(expenseLineItemRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public ExpenseLineItemResponse getById(UUID lineItemId) {
        return expenseLineItemMapper.toResponse(findEntity(lineItemId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ExpenseLineItemResponse> getAll(Pageable pageable) {
        return expenseLineItemRepository.findAll(pageable).map(expenseLineItemMapper::toResponse);
    }

    @Override
    public void delete(UUID lineItemId) {
        expenseLineItemRepository.delete(findEntity(lineItemId));
    }

    private void applyRelations(ExpenseLineItem entity, ExpenseLineItemRequest request) {
        entity.setReport(expenseReportRepository.findById(request.reportId())
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseReport not found with id: " + request.reportId())));
        entity.setCategory(expenseCategoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseCategory not found with id: " + request.categoryId())));
        entity.setCurrency(currencyRepository.findById(request.currencyId())
                .orElseThrow(() -> new ResourceNotFoundException("Currency not found with id: " + request.currencyId())));

        UUID costCenterId = request.costCenterId();
        entity.setCostCenter(costCenterId == null ? null : costCenterRepository.findById(costCenterId)
                .orElseThrow(() -> new ResourceNotFoundException("CostCenter not found with id: " + costCenterId)));

        UUID projectId = request.projectId();
        entity.setProject(projectId == null ? null : projectCacheRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectCache not found with id: " + projectId)));
    }

    private ExpenseLineItem findEntity(UUID lineItemId) {
        return expenseLineItemRepository.findById(lineItemId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseLineItem not found with id: " + lineItemId));
    }
}
