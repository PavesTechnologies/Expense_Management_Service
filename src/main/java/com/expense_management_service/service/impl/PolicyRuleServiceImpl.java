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

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class PolicyRuleServiceImpl implements PolicyRuleService {

    private final PolicyRuleRepository policyRuleRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final PolicyRuleMapper policyRuleMapper;

    @Override
    public PolicyRuleResponse create(PolicyRuleRequest request) {
        PolicyRule entity = policyRuleMapper.toEntity(request);
        entity.setCategory(findCategory(request.categoryId()));
        return policyRuleMapper.toResponse(policyRuleRepository.save(entity));
    }

    @Override
    public PolicyRuleResponse update(UUID policyId, PolicyRuleRequest request) {
        PolicyRule entity = findEntity(policyId);
        policyRuleMapper.updateEntity(entity, request);
        entity.setCategory(findCategory(request.categoryId()));
        return policyRuleMapper.toResponse(policyRuleRepository.save(entity));
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
    public void delete(UUID policyId) {
        policyRuleRepository.delete(findEntity(policyId));
    }

    private ExpenseCategory findCategory(UUID categoryId) {
        return expenseCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseCategory not found with id: " + categoryId));
    }

    private PolicyRule findEntity(UUID policyId) {
        return policyRuleRepository.findById(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("PolicyRule not found with id: " + policyId));
    }
}
