package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.PolicyRuleLimitRequest;
import com.expense_management_service.dto.request.PolicyRuleRequest;
import com.expense_management_service.dto.response.PolicyRuleResponse;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.Policy;
import com.expense_management_service.entity.PolicyRule;
import com.expense_management_service.entity.PolicyRuleLimit;
import com.expense_management_service.enums.PolicyEnforcementType;
import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.enums.PolicySeverity;
import com.expense_management_service.mapper.PolicyRuleMapper;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.PolicyRepository;
import com.expense_management_service.repository.PolicyRuleLimitRepository;
import com.expense_management_service.repository.PolicyRuleRepository;
import com.expense_management_service.service.PolicyVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyRuleServiceImplTest {

    @Mock
    private PolicyRuleRepository policyRuleRepository;
    @Mock
    private ExpenseCategoryRepository expenseCategoryRepository;
    @Mock
    private PolicyRepository policyRepository;
    @Mock
    private PolicyRuleLimitRepository policyRuleLimitRepository;
    @Mock
    private CurrencyRepository currencyRepository;
    @Mock
    private PolicyVersionService policyVersionService;

    private PolicyRuleServiceImpl policyRuleService;

    private UUID categoryId;
    private ExpenseCategory activeCategory;

    @BeforeEach
    void setUp() {
        policyRuleService = new PolicyRuleServiceImpl(policyRuleRepository, expenseCategoryRepository, policyRepository,
                policyRuleLimitRepository, currencyRepository, policyVersionService, new PolicyRuleMapper());
        categoryId = UUID.randomUUID();
        activeCategory = ExpenseCategory.builder().categoryId(categoryId).categoryName("Travel").status("ACTIVE").build();
        Policy defaultPolicy = Policy.builder().policyId(UUID.randomUUID()).policyName("Default Policy").status("ACTIVE").build();
        lenient().when(policyRepository.findByPolicyName("Default Policy")).thenReturn(Optional.of(defaultPolicy));
    }

    private PolicyRuleRequest validRequest() {
        return new PolicyRuleRequest(null, categoryId, "Travel over-limit", PolicyRuleType.AMOUNT_LIMIT, "500",
                PolicySeverity.WARN, null, null, null, "ACTIVE", null);
    }

    @Test
    void create_savesRule_whenValid() {
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory));
        when(policyRuleRepository.save(any(PolicyRule.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyRuleResponse response = policyRuleService.create(validRequest());

        assertThat(response.ruleType()).isEqualTo(PolicyRuleType.AMOUNT_LIMIT);
        assertThat(response.severity()).isEqualTo(PolicySeverity.WARN);
        assertThat(response.categoryId()).isEqualTo(categoryId);
    }

    @Test
    void create_defaultsToSeededDefaultPolicy_whenPolicyBundleIdOmitted() {
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory));
        when(policyRuleRepository.save(any(PolicyRule.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyRuleResponse response = policyRuleService.create(validRequest());

        UUID defaultPolicyId = policyRepository.findByPolicyName("Default Policy").orElseThrow().getPolicyId();
        assertThat(response.policyBundleId()).isEqualTo(defaultPolicyId);
    }

    @Test
    void create_usesExplicitPolicyBundle_whenProvided() {
        UUID explicitBundleId = UUID.randomUUID();
        Policy explicitPolicy = Policy.builder().policyId(explicitBundleId).policyName("Field Sales Policy").status("ACTIVE").build();
        when(policyRepository.findById(explicitBundleId)).thenReturn(Optional.of(explicitPolicy));
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory));
        when(policyRuleRepository.save(any(PolicyRule.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyRuleRequest request = new PolicyRuleRequest(explicitBundleId, categoryId, "Field sales meal cap",
                PolicyRuleType.AMOUNT_LIMIT, "1500", PolicySeverity.WARN, null, null, null, "ACTIVE", null);

        PolicyRuleResponse response = policyRuleService.create(request);

        assertThat(response.policyBundleId()).isEqualTo(explicitBundleId);
    }

    @Test
    void create_throwsResourceNotFound_whenExplicitPolicyBundleMissing() {
        UUID missingBundleId = UUID.randomUUID();
        when(policyRepository.findById(missingBundleId)).thenReturn(Optional.empty());
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory));

        PolicyRuleRequest request = new PolicyRuleRequest(missingBundleId, categoryId, "Travel over-limit",
                PolicyRuleType.AMOUNT_LIMIT, "500", PolicySeverity.WARN, null, null, null, "ACTIVE", null);

        assertThatThrownBy(() -> policyRuleService.create(request)).isInstanceOf(ResourceNotFoundException.class);

        verify(policyRuleRepository, never()).save(any());
    }

    @Test
    void create_throwsIllegalState_whenDefaultPolicyMissingEntirely() {
        when(policyRepository.findByPolicyName("Default Policy")).thenReturn(Optional.empty());
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory));

        assertThatThrownBy(() -> policyRuleService.create(validRequest())).isInstanceOf(IllegalStateException.class);

        verify(policyRuleRepository, never()).save(any());
    }

    @Test
    void create_defaultsSeverityToInfo_forDuplicateExpenseWhenUnspecified() {
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory));
        when(policyRuleRepository.save(any(PolicyRule.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyRuleRequest request = new PolicyRuleRequest(null, categoryId, "Possible duplicate", PolicyRuleType.DUPLICATE_EXPENSE,
                null, null, null, null, null, "ACTIVE", null);

        PolicyRuleResponse response = policyRuleService.create(request);

        assertThat(response.severity()).isEqualTo(PolicySeverity.INFO);
    }

    @Test
    void create_defaultsSeverityToWarn_whenUnspecifiedForOtherTypes() {
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory));
        when(policyRuleRepository.save(any(PolicyRule.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyRuleRequest request = new PolicyRuleRequest(null, categoryId, "Missing description", PolicyRuleType.MISSING_DESCRIPTION,
                null, null, null, null, null, "ACTIVE", null);

        PolicyRuleResponse response = policyRuleService.create(request);

        assertThat(response.severity()).isEqualTo(PolicySeverity.WARN);
    }

    @Test
    void create_defaultsEnforcementTypeToWarn_whenUnspecified() {
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory));
        when(policyRuleRepository.save(any(PolicyRule.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyRuleResponse response = policyRuleService.create(validRequest());

        assertThat(response.enforcementType()).isEqualTo(PolicyEnforcementType.WARN);
    }

    @Test
    void create_respectsExplicitBlockEnforcementType() {
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory));
        when(policyRuleRepository.save(any(PolicyRule.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyRuleRequest request = new PolicyRuleRequest(null, categoryId, "Hotel hard cap", PolicyRuleType.AMOUNT_LIMIT,
                "10000", PolicySeverity.WARN, PolicyEnforcementType.BLOCK, null, null, "ACTIVE", null);

        PolicyRuleResponse response = policyRuleService.create(request);

        assertThat(response.enforcementType()).isEqualTo(PolicyEnforcementType.BLOCK);
    }

    @Test
    void create_savesConfiguredCurrencyLimits_whenProvided() {
        UUID currencyId = UUID.randomUUID();
        Currency currency = Currency.builder().currencyId(currencyId).currencyCode("INR").decimalPlaces(2).build();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory));
        when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(currency));
        when(policyRuleRepository.save(any(PolicyRule.class))).thenAnswer(inv -> inv.getArgument(0));
        when(policyRuleLimitRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        PolicyRuleRequest request = new PolicyRuleRequest(null, categoryId, "Meals cap", PolicyRuleType.AMOUNT_LIMIT,
                "999999", PolicySeverity.WARN, null, null, null, "ACTIVE",
                List.of(new PolicyRuleLimitRequest(currencyId, new BigDecimal("1500"))));

        PolicyRuleResponse response = policyRuleService.create(request);

        assertThat(response.limits()).hasSize(1);
        assertThat(response.limits().get(0).currencyCode()).isEqualTo("INR");
        assertThat(response.limits().get(0).limitAmount()).isEqualByComparingTo("1500");
    }

    @Test
    void update_clearsLimits_backToFlatMode_whenLimitsOmitted() {
        UUID policyId = UUID.randomUUID();
        PolicyRule existing = PolicyRule.builder().policyId(policyId).category(activeCategory)
                .ruleType(PolicyRuleType.AMOUNT_LIMIT).ruleValue("500").severity(PolicySeverity.WARN).build();
        PolicyRuleLimit staleLimit = PolicyRuleLimit.builder().limitId(UUID.randomUUID()).policyRule(existing).build();
        when(policyRuleRepository.findById(policyId)).thenReturn(Optional.of(existing));
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory));
        when(policyRuleRepository.save(any(PolicyRule.class))).thenAnswer(inv -> inv.getArgument(0));
        when(policyRuleLimitRepository.findByPolicyRule_PolicyId(policyId)).thenReturn(List.of(staleLimit));

        PolicyRuleResponse response = policyRuleService.update(policyId, validRequest());

        verify(policyRuleLimitRepository).deleteAll(List.of(staleLimit));
        assertThat(response.limits()).isEmpty();
    }

    @Test
    void create_throwsIllegalArgument_whenCategoryInactive() {
        ExpenseCategory inactive = ExpenseCategory.builder().categoryId(categoryId).categoryName("Travel").status("INACTIVE").build();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> policyRuleService.create(validRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not Active");

        verify(policyRuleRepository, never()).save(any());
    }

    @Test
    void create_throwsResourceNotFound_whenCategoryMissing() {
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> policyRuleService.create(validRequest()))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(policyRuleRepository, never()).save(any());
    }

    @Test
    void create_throwsIllegalArgument_whenEffectiveFromAfterEffectiveTo() {
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory));
        PolicyRuleRequest request = new PolicyRuleRequest(null, categoryId, "Travel over-limit", PolicyRuleType.AMOUNT_LIMIT, "500",
                PolicySeverity.WARN, null, LocalDate.now(), LocalDate.now().minusDays(1), "ACTIVE", null);

        assertThatThrownBy(() -> policyRuleService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effectiveFrom");

        verify(policyRuleRepository, never()).save(any());
    }

    @Test
    void update_updatesRule_whenValid() {
        UUID policyId = UUID.randomUUID();
        PolicyRule existing = PolicyRule.builder().policyId(policyId).category(activeCategory)
                .ruleType(PolicyRuleType.AMOUNT_LIMIT).ruleValue("500").severity(PolicySeverity.WARN).build();
        when(policyRuleRepository.findById(policyId)).thenReturn(Optional.of(existing));
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory));
        when(policyRuleRepository.save(any(PolicyRule.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyRuleResponse response = policyRuleService.update(policyId, validRequest());

        assertThat(response.ruleValue()).isEqualTo("500");
    }

    @Test
    void create_activatesANewPolicyVersion() {
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory));
        when(policyRuleRepository.save(any(PolicyRule.class))).thenAnswer(inv -> inv.getArgument(0));

        policyRuleService.create(validRequest());

        verify(policyVersionService).activateNewVersion(any(Policy.class));
    }

    @Test
    void update_activatesANewPolicyVersion() {
        UUID policyId = UUID.randomUUID();
        PolicyRule existing = PolicyRule.builder().policyId(policyId).category(activeCategory)
                .ruleType(PolicyRuleType.AMOUNT_LIMIT).ruleValue("500").severity(PolicySeverity.WARN).build();
        when(policyRuleRepository.findById(policyId)).thenReturn(Optional.of(existing));
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory));
        when(policyRuleRepository.save(any(PolicyRule.class))).thenAnswer(inv -> inv.getArgument(0));

        policyRuleService.update(policyId, validRequest());

        verify(policyVersionService).activateNewVersion(any(Policy.class));
    }

    @Test
    void delete_activatesANewPolicyVersion_whenTheRuleBelongedToAPolicy() {
        UUID policyId = UUID.randomUUID();
        Policy owningPolicy = Policy.builder().policyId(UUID.randomUUID()).policyName("Field Sales Policy").build();
        PolicyRule existing = PolicyRule.builder().policyId(policyId).category(activeCategory).policy(owningPolicy).build();
        when(policyRuleRepository.findById(policyId)).thenReturn(Optional.of(existing));

        policyRuleService.delete(policyId);

        verify(policyVersionService).activateNewVersion(owningPolicy);
    }

    @Test
    void delete_removesRule_whenFound() {
        UUID policyId = UUID.randomUUID();
        PolicyRule existing = PolicyRule.builder().policyId(policyId).category(activeCategory).build();
        when(policyRuleRepository.findById(policyId)).thenReturn(Optional.of(existing));

        policyRuleService.delete(policyId);

        verify(policyRuleRepository).delete(existing);
        verify(policyVersionService, never()).activateNewVersion(any());
    }

    @Test
    void delete_throwsResourceNotFound_whenMissing() {
        UUID policyId = UUID.randomUUID();
        when(policyRuleRepository.findById(policyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> policyRuleService.delete(policyId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getAllForCategory_filtersToSingleCategory() {
        PolicyRule rule = PolicyRule.builder().policyId(UUID.randomUUID()).category(activeCategory)
                .ruleType(PolicyRuleType.AMOUNT_LIMIT).severity(PolicySeverity.WARN).build();
        when(policyRuleRepository.findByCategory_CategoryId(categoryId)).thenReturn(List.of(rule));

        List<PolicyRuleResponse> responses = policyRuleService.getAllForCategory(categoryId);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).categoryId()).isEqualTo(categoryId);
    }
}
