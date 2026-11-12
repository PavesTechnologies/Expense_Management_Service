package com.expense_management_service.service.impl;

import java.util.List;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.PolicyRuleLimitRequest;
import com.expense_management_service.dto.request.PolicyRuleRequest;
import com.expense_management_service.dto.response.PolicyRuleResponse;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.Policy;
import com.expense_management_service.entity.PolicyRule;
import com.expense_management_service.entity.PolicyRuleLimit;
import com.expense_management_service.mapper.PolicyRuleMapper;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.PolicyRepository;
import com.expense_management_service.repository.PolicyRuleLimitRepository;
import com.expense_management_service.repository.PolicyRuleRepository;
import com.expense_management_service.service.PolicyRuleService;
import com.expense_management_service.service.PolicyVersionService;
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
    private static final String DEFAULT_POLICY_NAME = "Default Policy";

    private final PolicyRuleRepository policyRuleRepository;
    private final ExpenseCategoryRepository expenseCategoryRepository;
    private final PolicyRepository policyRepository;
    private final PolicyRuleLimitRepository policyRuleLimitRepository;
    private final CurrencyRepository currencyRepository;
    private final PolicyVersionService policyVersionService;
    private final PolicyRuleMapper policyRuleMapper;

    @Override
    public PolicyRuleResponse create(PolicyRuleRequest request) {
        ExpenseCategory category = findActiveCategory(request.categoryId());
        Policy policy = findPolicyBundle(request.policyBundleId());
        assertEffectiveDatesValid(request);

        PolicyRule entity = policyRuleMapper.toEntity(request);
        entity.setCategory(category);
        entity.setPolicy(policy);
        PolicyRule saved = policyRuleRepository.save(entity);
        List<PolicyRuleLimit> limits = replaceLimits(saved, request.limits());
        int newVersion = policyVersionService.activateNewVersion(policy);
        log.info("Created policy rule {} ({}) for category {} in policy {} (now version {})",
                saved.getPolicyId(), saved.getRuleType(), category.getCategoryId(), policy.getPolicyId(), newVersion);
        return policyRuleMapper.toResponse(saved, limits);
    }

    @Override
    public PolicyRuleResponse update(UUID policyId, PolicyRuleRequest request) {
        ExpenseCategory category = findActiveCategory(request.categoryId());
        Policy policy = findPolicyBundle(request.policyBundleId());
        assertEffectiveDatesValid(request);

        PolicyRule entity = findEntity(policyId);
        policyRuleMapper.updateEntity(entity, request);
        entity.setCategory(category);
        entity.setPolicy(policy);
        PolicyRule saved = policyRuleRepository.save(entity);
        List<PolicyRuleLimit> limits = replaceLimits(saved, request.limits());
        int newVersion = policyVersionService.activateNewVersion(policy);
        log.info("Updated policy rule {} (policy {} now version {})", policyId, policy.getPolicyId(), newVersion);
        return policyRuleMapper.toResponse(saved, limits);
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyRuleResponse getById(UUID policyId) {
        PolicyRule entity = findEntity(policyId);
        return policyRuleMapper.toResponse(entity, policyRuleLimitRepository.findByPolicyRule_PolicyId(policyId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PolicyRuleResponse> getAll() {
        return policyRuleRepository.findAll().stream()
                .map(rule -> policyRuleMapper.toResponse(rule, policyRuleLimitRepository.findByPolicyRule_PolicyId(rule.getPolicyId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PolicyRuleResponse> getAllForCategory(UUID categoryId) {
        return policyRuleRepository.findByCategory_CategoryId(categoryId).stream()
                .map(rule -> policyRuleMapper.toResponse(rule, policyRuleLimitRepository.findByPolicyRule_PolicyId(rule.getPolicyId())))
                .toList();
    }

    @Override
    public void delete(UUID policyId) {
        PolicyRule entity = findEntity(policyId);
        Policy policy = entity.getPolicy();
        policyRuleRepository.delete(entity);
        if (policy != null) {
            policyVersionService.activateNewVersion(policy);
        }
        log.info("Deleted policy rule {}", policyId);
    }

    /**
     * Replaces this rule's entire currency-limit set - delete-then-insert, matching the same
     * replace-all-children pattern used for {@code PolicyViolation} recomputation elsewhere in this
     * codebase. A null or empty {@code limitRequests} clears the rule back to legacy flat-limit
     * mode (using {@code ruleValue}), rather than leaving stale rows behind.
     */
    private List<PolicyRuleLimit> replaceLimits(PolicyRule rule, List<PolicyRuleLimitRequest> limitRequests) {
        policyRuleLimitRepository.deleteAll(policyRuleLimitRepository.findByPolicyRule_PolicyId(rule.getPolicyId()));
        if (limitRequests == null || limitRequests.isEmpty()) {
            return List.of();
        }
        List<PolicyRuleLimit> toSave = limitRequests.stream()
                .map(limitRequest -> PolicyRuleLimit.builder()
                        .policyRule(rule)
                        .currency(findCurrency(limitRequest.currencyId()))
                        .limitAmount(limitRequest.limitAmount())
                        .build())
                .toList();
        return policyRuleLimitRepository.saveAll(toSave);
    }

    private Currency findCurrency(UUID currencyId) {
        return currencyRepository.findById(currencyId)
                .orElseThrow(() -> new ResourceNotFoundException("Currency not found with id: " + currencyId));
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

    /**
     * Resolves the {@link Policy} bundle a rule belongs to. Falls back to the seeded "Default
     * Policy" when the request omits {@code policyBundleId} — this is what keeps every pre-bundle
     * API client working unmodified, since every existing rule already lives in that same bundle.
     */
    private Policy findPolicyBundle(UUID policyBundleId) {
        if (policyBundleId != null) {
            return policyRepository.findById(policyBundleId)
                    .orElseThrow(() -> new ResourceNotFoundException("Policy not found with id: " + policyBundleId));
        }
        return policyRepository.findByPolicyName(DEFAULT_POLICY_NAME)
                .orElseThrow(() -> new IllegalStateException(
                        "'" + DEFAULT_POLICY_NAME + "' is missing - the Policy bundle seed migration should have created it"));
    }
}
