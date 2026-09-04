package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.PolicyRuleRequest;
import com.expense_management_service.dto.response.PolicyRuleResponse;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.PolicyRule;
import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.enums.PolicySeverity;
import com.expense_management_service.mapper.PolicyRuleMapper;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.PolicyRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyRuleServiceImplTest {

    @Mock
    private PolicyRuleRepository policyRuleRepository;
    @Mock
    private ExpenseCategoryRepository expenseCategoryRepository;

    private PolicyRuleServiceImpl policyRuleService;

    private UUID categoryId;
    private ExpenseCategory activeCategory;

    @BeforeEach
    void setUp() {
        policyRuleService = new PolicyRuleServiceImpl(policyRuleRepository, expenseCategoryRepository, new PolicyRuleMapper());
        categoryId = UUID.randomUUID();
        activeCategory = ExpenseCategory.builder().categoryId(categoryId).categoryName("Travel").status("ACTIVE").build();
    }

    private PolicyRuleRequest validRequest() {
        return new PolicyRuleRequest(categoryId, "Travel over-limit", PolicyRuleType.AMOUNT_LIMIT, "500",
                PolicySeverity.WARN, null, null, "ACTIVE");
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
    void create_defaultsSeverityToInfo_forDuplicateExpenseWhenUnspecified() {
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory));
        when(policyRuleRepository.save(any(PolicyRule.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyRuleRequest request = new PolicyRuleRequest(categoryId, "Possible duplicate", PolicyRuleType.DUPLICATE_EXPENSE,
                null, null, null, null, "ACTIVE");

        PolicyRuleResponse response = policyRuleService.create(request);

        assertThat(response.severity()).isEqualTo(PolicySeverity.INFO);
    }

    @Test
    void create_defaultsSeverityToWarn_whenUnspecifiedForOtherTypes() {
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory));
        when(policyRuleRepository.save(any(PolicyRule.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyRuleRequest request = new PolicyRuleRequest(categoryId, "Missing description", PolicyRuleType.MISSING_DESCRIPTION,
                null, null, null, null, "ACTIVE");

        PolicyRuleResponse response = policyRuleService.create(request);

        assertThat(response.severity()).isEqualTo(PolicySeverity.WARN);
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
        PolicyRuleRequest request = new PolicyRuleRequest(categoryId, "Travel over-limit", PolicyRuleType.AMOUNT_LIMIT, "500",
                PolicySeverity.WARN, LocalDate.now(), LocalDate.now().minusDays(1), "ACTIVE");

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
    void delete_removesRule_whenFound() {
        UUID policyId = UUID.randomUUID();
        PolicyRule existing = PolicyRule.builder().policyId(policyId).category(activeCategory).build();
        when(policyRuleRepository.findById(policyId)).thenReturn(Optional.of(existing));

        policyRuleService.delete(policyId);

        verify(policyRuleRepository).delete(existing);
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
