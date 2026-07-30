package com.expense_management_service.service.impl;

import java.util.List;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.PolicyRuleRequest;
import com.expense_management_service.dto.response.PolicyRuleResponse;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.PolicyRule;
import com.expense_management_service.mapper.PolicyRuleMapper;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.PolicyRuleRepository;
import com.expense_management_service.service.PolicyRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PolicyRuleServiceImpl implements PolicyRuleService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final PolicyRuleRepository policyRuleRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final PolicyRuleMapper policyRuleMapper;

    @Override
    public PolicyRuleResponse create(PolicyRuleRequest request) {
        ExpenseCategory category = findActiveCategory(request.categoryId());
        assertEffectiveDatesValid(request);

        PolicyRule entity = policyRuleMapper.toEntity(request);
        entity.setCategory(category);
        PolicyRule saved = policyRuleRepository.save(entity);
        log.info("Created policy rule {} ({}) for category {}", saved.getPolicyId(), saved.getRuleType(), category.getCategoryId());
        return policyRuleMapper.toResponse(saved);
    }

    @Override
    public PolicyRuleResponse update(UUID policyId, PolicyRuleRequest request) {
        ExpenseCategory category = findActiveCategory(request.categoryId());
        assertEffectiveDatesValid(request);

        PolicyRule entity = findEntity(policyId);
        policyRuleMapper.updateEntity(entity, request);
        entity.setCategory(category);
        PolicyRule saved = policyRuleRepository.save(entity);
        log.info("Updated policy rule {}", policyId);
        return policyRuleMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyRuleResponse getById(UUID policyId) {
        return policyRuleMapper.toResponse(findEntity(policyId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PolicyRuleResponse> getAll() {
        return policyRuleRepository.findAll().stream().map(policyRuleMapper::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PolicyRuleResponse> getAllForCategory(UUID categoryId) {
        return policyRuleRepository.findByCategory_CategoryId(categoryId).stream().map(policyRuleMapper::toResponse).toList();
    }

    @Override
    public void delete(UUID policyId) {
        policyRuleRepository.delete(findEntity(policyId));
        log.info("Deleted policy rule {}", policyId);
    }

    private void assertEffectiveDatesValid(PolicyRuleRequest request) {
        if (request.effectiveFrom() != null && request.effectiveTo() != null
                && request.effectiveFrom().isAfter(request.effectiveTo())) {
            throw new IllegalArgumentException("effectiveFrom cannot be after effectiveTo");
        }
    }

    private ExpenseCategory findActiveCategory(UUID categoryId) {
        ExpenseCategory category = expenseCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseCategory not found with id: " + categoryId));
        if (!STATUS_ACTIVE.equalsIgnoreCase(category.getStatus())) {
            throw new IllegalArgumentException(
                    "Expense category " + category.getCategoryName() + " is not Active and cannot have policy rules attached");
        }
        return category;
    }

    private PolicyRule findEntity(UUID policyId) {
        return policyRuleRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("PolicyRule not found with id: " + policyId));
    }
}
