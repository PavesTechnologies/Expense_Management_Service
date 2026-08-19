package com.expense_management_service.service.impl;

import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.GlAccount;
import com.expense_management_service.entity.PolicyViolation;
import com.expense_management_service.enums.PolicyEnforcementType;
import com.expense_management_service.repository.PolicyViolationRepository;
import com.expense_management_service.service.FinanceEligibilityResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultFinanceVerificationEligibilityCheckerImplTest {

    @Mock private PolicyViolationRepository policyViolationRepository;

    private DefaultFinanceVerificationEligibilityCheckerImpl checker;

    @BeforeEach
    void setUp() {
        checker = new DefaultFinanceVerificationEligibilityCheckerImpl(policyViolationRepository);
    }

    private ExpenseLineItem lineItem(boolean receiptRequired, boolean hasReceipt, String glAccountStatus) {
        GlAccount glAccount = GlAccount.builder().glAccountId(UUID.randomUUID()).glAccountCode("6100").status(glAccountStatus).build();
        ExpenseCategory category = ExpenseCategory.builder().categoryName("Travel Meals").receiptRequired(receiptRequired).glAccount(glAccount).build();
        return ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).category(category)
                .receipts(hasReceipt ? List.of(com.expense_management_service.entity.Receipt.builder().receiptId(UUID.randomUUID()).build()) : List.of())
                .build();
    }

    @Test
    void check_blocksVerify_whenReceiptRequiredButMissing() {
        ExpenseLineItem lineItem = lineItem(true, false, "ACTIVE");

        FinanceEligibilityResult result = checker.check(lineItem);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).contains("Receipt is missing");
    }

    @Test
    void check_allowsVerify_whenReceiptNotRequiredAndMissing() {
        ExpenseLineItem lineItem = lineItem(false, false, "ACTIVE");
        when(policyViolationRepository.findByLineItem_LineItemId(lineItem.getLineItemId())).thenReturn(List.of());

        assertThat(checker.check(lineItem).eligible()).isTrue();
    }

    @Test
    void check_blocksVerify_whenWarnViolationHasNoJustification() {
        ExpenseLineItem lineItem = lineItem(false, false, "ACTIVE");
        PolicyViolation warn = PolicyViolation.builder().enforcementType(PolicyEnforcementType.WARN).justification(null).build();
        when(policyViolationRepository.findByLineItem_LineItemId(lineItem.getLineItemId())).thenReturn(List.of(warn));

        FinanceEligibilityResult result = checker.check(lineItem);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).contains("Policy exception has not been resolved");
    }

    @Test
    void check_allowsVerify_whenWarnViolationHasJustification() {
        ExpenseLineItem lineItem = lineItem(false, false, "ACTIVE");
        PolicyViolation warn = PolicyViolation.builder().enforcementType(PolicyEnforcementType.WARN).justification("Approved by manager").build();
        when(policyViolationRepository.findByLineItem_LineItemId(lineItem.getLineItemId())).thenReturn(List.of(warn));

        assertThat(checker.check(lineItem).eligible()).isTrue();
    }

    @Test
    void check_blocksVerify_whenBlockViolationExists_evenWithoutJustification() {
        ExpenseLineItem lineItem = lineItem(false, false, "ACTIVE");
        PolicyViolation block = PolicyViolation.builder().enforcementType(PolicyEnforcementType.BLOCK).build();
        when(policyViolationRepository.findByLineItem_LineItemId(lineItem.getLineItemId())).thenReturn(List.of(block));

        assertThat(checker.check(lineItem).eligible()).isFalse();
    }

    @Test
    void check_blocksVerify_whenGlAccountInactive() {
        ExpenseLineItem lineItem = lineItem(false, false, "INACTIVE");
        when(policyViolationRepository.findByLineItem_LineItemId(lineItem.getLineItemId())).thenReturn(List.of());

        FinanceEligibilityResult result = checker.check(lineItem);

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).contains("inactive GL Account");
    }

    @Test
    void check_isEligible_whenReceiptPresentPolicyResolvedAndGlAccountActive() {
        ExpenseLineItem lineItem = lineItem(true, true, "ACTIVE");
        when(policyViolationRepository.findByLineItem_LineItemId(lineItem.getLineItemId())).thenReturn(List.of());

        assertThat(checker.check(lineItem).eligible()).isTrue();
    }
}
