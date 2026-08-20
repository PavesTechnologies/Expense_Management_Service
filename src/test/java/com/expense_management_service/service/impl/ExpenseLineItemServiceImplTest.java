package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ExpenseLineItemRequest;
import com.expense_management_service.dto.response.ExchangeRateResponse;
import com.expense_management_service.dto.response.ExpenseLineItemResponse;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.mapper.ExpenseLineItemMapper;
import com.expense_management_service.repository.CostCenterRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.entity.PolicyViolation;
import com.expense_management_service.enums.PolicyEnforcementType;
import com.expense_management_service.enums.PolicyRuleType;
import com.expense_management_service.enums.PolicySeverity;
import com.expense_management_service.mapper.PolicyViolationMapper;
import com.expense_management_service.repository.PolicyViolationRepository;
import com.expense_management_service.repository.ProjectCacheRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.ExchangeRateService;
import com.expense_management_service.service.PolicyEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpenseLineItemServiceImplTest {

    @Mock
    private ExpenseLineItemRepository expenseLineItemRepository;
    @Mock
    private ExpenseReportRepository expenseReportRepository;
    @Mock
    private ExpenseCategoryRepository expenseCategoryRepository;
    @Mock
    private CurrencyRepository currencyRepository;
    @Mock
    private CostCenterRepository costCenterRepository;
    @Mock
    private ProjectCacheRepository projectCacheRepository;
    @Mock
    private ExchangeRateService exchangeRateService;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private PolicyEvaluator policyEvaluator;
    @Mock
    private PolicyViolationRepository policyViolationRepository;

    private ExpenseLineItemServiceImpl expenseLineItemService;

    private final String employeeId = "5100014";
    private UUID reportId;
    private UUID categoryId;
    /** The organization base currency's id â€” same UUID as {@link #currency}. Named for what it means to conversion, not for its role on the report. */
    private UUID currencyId;
    private ExpenseReport draftReport;
    /** The Organization Base Currency (INR) â€” every line item converts INTO this, regardless of the report's own currency. */
    private Currency currency;

    @BeforeEach
    void setUp() {
        expenseLineItemService = new ExpenseLineItemServiceImpl(
                expenseLineItemRepository, expenseReportRepository, expenseCategoryRepository, currencyRepository,
                costCenterRepository, projectCacheRepository, exchangeRateService, currentUserService,
                new ExpenseLineItemMapper(), policyEvaluator, policyViolationRepository, new PolicyViolationMapper());
        ReflectionTestUtils.setField(expenseLineItemService, "baseCurrencyCode", "INR");

        reportId = UUID.randomUUID();
        categoryId = UUID.randomUUID();
        currencyId = UUID.randomUUID();
        currency = Currency.builder().currencyId(currencyId).currencyCode("INR").status("ACTIVE").build();
        draftReport = ExpenseReport.builder().reportId(reportId).employeeId(employeeId)
                .reportStatus(ReportStatus.DRAFT).currency(currency).build();

        // Not every test reaches currency conversion (some fail earlier on ownership/status/category
        // checks) â€” lenient() so those don't trip MockitoExtension's unnecessary-stubbing check.
        lenient().when(currencyRepository.findByCurrencyCodeIgnoreCase("INR")).thenReturn(Optional.of(currency));
    }

    private CurrentUser employeeCaller() {
        return new CurrentUser(UUID.randomUUID(), employeeId, "jordan@example.com", "Jordan", List.of("GENERAL"), List.of());
    }

    private ExpenseCategory activeCategory(String code) {
        return ExpenseCategory.builder().categoryId(categoryId).categoryCode(code).categoryName("Travel").status("ACTIVE").build();
    }

    private ExpenseLineItemRequest validRequest() {
        return new ExpenseLineItemRequest(categoryId, LocalDate.now().minusDays(1), "Uber", "Client meeting",
                new BigDecimal("100.00"), currencyId, null, null, null, false);
    }

    private void stubOwnerAndReport() {
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));
    }

    @Test
    void create_savesLineItem_whenValid() {
        stubOwnerAndReport();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory("TRAVEL")));
        when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(currency));
        when(expenseLineItemRepository.save(any(ExpenseLineItem.class))).thenAnswer(inv -> inv.getArgument(0));

        ExpenseLineItemResponse response = expenseLineItemService.create(reportId, validRequest());

        assertThat(response.amount()).isEqualByComparingTo("100.00");
        assertThat(response.categoryActive()).isTrue();
    }

    @Test
    void create_savesLineItem_whenProjectIdAndCostCenterIdOmitted() {
        stubOwnerAndReport();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory("TRAVEL")));
        when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(currency));
        when(expenseLineItemRepository.save(any(ExpenseLineItem.class))).thenAnswer(inv -> inv.getArgument(0));

        ExpenseLineItemResponse response = expenseLineItemService.create(reportId, validRequest());

        assertThat(response.projectId()).isNull();
        assertThat(response.costCenterId()).isNull();
        verify(costCenterRepository, never()).findById(any());
        verify(projectCacheRepository, never()).findById(any());
    }

    @Test
    void create_throwsIllegalArgumentException_whenCategoryInactive() {
        stubOwnerAndReport();
        ExpenseCategory inactive = ExpenseCategory.builder().categoryId(categoryId).categoryCode("TRAVEL")
                .categoryName("Travel").status("INACTIVE").build();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> expenseLineItemService.create(reportId, validRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not Active");

        verify(expenseLineItemRepository, never()).save(any());
    }

    @Test
    void create_throwsIllegalArgumentException_whenExpenseDateInFuture() {
        stubOwnerAndReport();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory("TRAVEL")));

        ExpenseLineItemRequest futureDated = new ExpenseLineItemRequest(categoryId, LocalDate.now().plusDays(1), "Uber",
                "desc", new BigDecimal("10"), currencyId, null, null, null, false);

        assertThatThrownBy(() -> expenseLineItemService.create(reportId, futureDated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");

        verify(expenseLineItemRepository, never()).save(any());
    }

    @Test
    void create_throwsBusinessRuleViolation_whenReportNotEditable() {
        draftReport.setReportStatus(ReportStatus.PENDING_APPROVAL);
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));

        assertThatThrownBy(() -> expenseLineItemService.create(reportId, validRequest()))
                .isInstanceOf(BusinessRuleViolationException.class);

        verify(expenseLineItemRepository, never()).save(any());
    }

    @Test
    void create_throwsAccessDenied_whenCallerDoesNotOwnParentReport() {
        draftReport.setEmployeeId("someone-else");
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));

        assertThatThrownBy(() -> expenseLineItemService.create(reportId, validRequest()))
                .isInstanceOf(AccessDeniedException.class);

        verify(expenseLineItemRepository, never()).save(any());
    }

    @Test
    void create_throwsResourceNotFoundException_whenReportMissing() {
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> expenseLineItemService.create(reportId, validRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void update_allowsSavingUnchangedInactiveCategory_butFlagsItInResponse() {
        UUID lineItemId = UUID.randomUUID();
        ExpenseCategory inactiveCategory = ExpenseCategory.builder().categoryId(categoryId).categoryCode("TRAVEL")
                .categoryName("Travel").status("INACTIVE").build();
        ExpenseLineItem existing = ExpenseLineItem.builder().lineItemId(lineItemId).report(draftReport)
                .category(inactiveCategory).amount(new BigDecimal("50")).currency(currency).build();

        stubOwnerAndReport();
        when(expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId))
                .thenReturn(Optional.of(existing));
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(inactiveCategory));
        when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(currency));
        when(expenseLineItemRepository.save(any(ExpenseLineItem.class))).thenAnswer(inv -> inv.getArgument(0));

        ExpenseLineItemResponse response = expenseLineItemService.update(reportId, lineItemId, validRequest());

        assertThat(response.categoryActive()).isFalse();
    }

    @Test
    void delete_removesLineItem_whenReportEditableAndOwner() {
        UUID lineItemId = UUID.randomUUID();
        ExpenseLineItem existing = ExpenseLineItem.builder().lineItemId(lineItemId).report(draftReport).build();
        stubOwnerAndReport();
        when(expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId))
                .thenReturn(Optional.of(existing));

        expenseLineItemService.delete(reportId, lineItemId);

        verify(expenseLineItemRepository).delete(existing);
    }

    @Test
    void delete_throwsBusinessRuleViolation_whenReportSubmitted() {
        UUID lineItemId = UUID.randomUUID();
        draftReport.setReportStatus(ReportStatus.PENDING_APPROVAL);
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));

        assertThatThrownBy(() -> expenseLineItemService.delete(reportId, lineItemId))
                .isInstanceOf(BusinessRuleViolationException.class);

        verify(expenseLineItemRepository, never()).delete(any());
    }

    @Test
    void getAllForReport_throwsAccessDenied_whenEmployeeDoesNotOwnReport() {
        draftReport.setEmployeeId("someone-else");
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));

        assertThatThrownBy(() -> expenseLineItemService.getAllForReport(reportId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getAllForReport_returnsItems_whenOwner() {
        stubOwnerAndReport();
        ExpenseLineItem item = ExpenseLineItem.builder().lineItemId(UUID.randomUUID()).report(draftReport)
                .category(activeCategory("TRAVEL")).amount(BigDecimal.TEN).currency(currency).build();
        when(expenseLineItemRepository.findByReport_ReportId(reportId)).thenReturn(List.of(item));

        assertThat(expenseLineItemService.getAllForReport(reportId)).hasSize(1);
    }

    /**
     * Regression: {@code assertViewable}'s privileged-reviewer whitelist previously omitted
     * FINANCE_EXECUTIVE/AP_EXECUTIVE (added after ADMIN/FINANCE/MANAGER), which meant an
     * AP_EXECUTIVE calling {@code ApPaymentServiceImpl.getPaymentDetails} - which delegates here
     * via {@code getAllForReport} - was wrongly denied on a report they don't personally own, even
     * though the report had correctly reached APPROVED_FOR_PAYMENT.
     */
    @Test
    void getAllForReport_returnsItems_forApExecutive_onSomeoneElsesReport() {
        draftReport.setEmployeeId("someone-else");
        when(currentUserService.getCurrentUser()).thenReturn(
                new CurrentUser(UUID.randomUUID(), "ap-user", "ap@example.com", "AP", List.of("AP_EXECUTIVE"), List.of()));
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));
        when(expenseLineItemRepository.findByReport_ReportId(reportId)).thenReturn(List.of());

        assertThat(expenseLineItemService.getAllForReport(reportId)).isEmpty();
    }

    @Test
    void getAllForReport_returnsItems_forFinanceExecutive_onSomeoneElsesReport() {
        draftReport.setEmployeeId("someone-else");
        when(currentUserService.getCurrentUser()).thenReturn(
                new CurrentUser(UUID.randomUUID(), "finance-user", "finance@example.com", "Finance", List.of("FINANCE_EXECUTIVE"), List.of()));
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(draftReport));
        when(expenseLineItemRepository.findByReport_ReportId(reportId)).thenReturn(List.of());

        assertThat(expenseLineItemService.getAllForReport(reportId)).isEmpty();
    }

    // --- EP05: policy evaluation is advisory-only and must never affect the save --------------

    @Test
    void create_savesLineItem_whenPolicyEvaluatorThrows() {
        stubOwnerAndReport();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory("TRAVEL")));
        when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(currency));
        when(expenseLineItemRepository.save(any(ExpenseLineItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(policyEvaluator.evaluate(any())).thenThrow(new RuntimeException("boom"));

        ExpenseLineItemResponse response = expenseLineItemService.create(reportId, validRequest());

        assertThat(response.amount()).isEqualByComparingTo("100.00");
        assertThat(response.policyWarnings()).isEmpty();
        verify(expenseLineItemRepository).save(any(ExpenseLineItem.class));
    }

    @Test
    void create_includesPolicyWarnings_inResponse() {
        stubOwnerAndReport();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory("TRAVEL")));
        when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(currency));
        when(expenseLineItemRepository.save(any(ExpenseLineItem.class))).thenAnswer(inv -> inv.getArgument(0));

        PolicyViolation violation = PolicyViolation.builder()
                .violationId(UUID.randomUUID())
                .ruleType(PolicyRuleType.MISSING_DESCRIPTION)
                .severity(PolicySeverity.WARN)
                .message("This expense is missing a description")
                .build();
        when(policyEvaluator.evaluate(any())).thenReturn(List.of(violation));
        when(policyViolationRepository.findByLineItem_LineItemId(any())).thenReturn(List.of(violation));

        ExpenseLineItemResponse response = expenseLineItemService.create(reportId, validRequest());

        assertThat(response.policyWarnings()).hasSize(1);
        assertThat(response.policyWarnings().get(0).message()).isEqualTo("This expense is missing a description");
    }

    @Test
    void update_preservesJustification_acrossRecompute() {
        UUID lineItemId = UUID.randomUUID();
        ExpenseCategory category = activeCategory("TRAVEL");
        ExpenseLineItem existingLineItem = ExpenseLineItem.builder().lineItemId(lineItemId).report(draftReport)
                .category(category).amount(new BigDecimal("50")).currency(currency).build();

        PolicyViolation existingViolation = PolicyViolation.builder()
                .violationId(UUID.randomUUID())
                .ruleType(PolicyRuleType.MISSING_DESCRIPTION)
                .severity(PolicySeverity.WARN)
                .message("This expense is missing a description")
                .justification("Client requested no memo")
                .justifiedAt(LocalDateTime.now().minusDays(1))
                .build();
        PolicyViolation recomputedViolation = PolicyViolation.builder()
                .ruleType(PolicyRuleType.MISSING_DESCRIPTION)
                .severity(PolicySeverity.WARN)
                .message("This expense is missing a description")
                .build();

        stubOwnerAndReport();
        when(expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId))
                .thenReturn(Optional.of(existingLineItem));
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
        when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(currency));
        when(expenseLineItemRepository.save(any(ExpenseLineItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(policyViolationRepository.findByLineItem_LineItemId(lineItemId)).thenReturn(List.of(existingViolation));
        when(policyEvaluator.evaluate(any())).thenReturn(List.of(recomputedViolation));

        expenseLineItemService.update(reportId, lineItemId, validRequest());

        assertThat(recomputedViolation.getJustification()).isEqualTo("Client requested no memo");
        assertThat(recomputedViolation.getJustifiedAt()).isNotNull();
        verify(policyViolationRepository).saveAll(List.of(recomputedViolation));
    }

    // ---- Phase 3: BLOCK enforcement flips lineStatus; WARN leaves it ACTIVE ------------------
    // PolicyEvaluator is mocked here (as in every other test in this class) because the amount-
    // limit threshold/overage math itself belongs to DefaultPolicyEvaluatorTest and must not be
    // re-verified here — only the wiring from a returned violation's enforcementType to
    // ExpenseLineItem.lineStatus is this class's concern. Request amounts are chosen to match the
    // scenario being illustrated even though the mocked evaluator doesn't actually inspect them.

    private ExpenseLineItemRequest requestWithAmount(BigDecimal amount) {
        return new ExpenseLineItemRequest(categoryId, LocalDate.now().minusDays(1), "Restaurant", "Team lunch",
                amount, currencyId, null, null, null, false);
    }

    private PolicyViolation amountLimitViolation(PolicyEnforcementType enforcementType) {
        return PolicyViolation.builder()
                .violationId(UUID.randomUUID())
                .ruleType(PolicyRuleType.AMOUNT_LIMIT)
                .severity(PolicySeverity.WARN)
                .enforcementType(enforcementType)
                .message("Amount exceeds the configured limit of 1500")
                .build();
    }

    @Test
    void create_lineStatusIsActive_whenAmountWithinLimit_noViolation() {
        stubOwnerAndReport();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory("TRAVEL")));
        when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(currency));
        when(expenseLineItemRepository.save(any(ExpenseLineItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(policyEvaluator.evaluate(any())).thenReturn(List.of());

        ExpenseLineItemResponse response = expenseLineItemService.create(reportId, requestWithAmount(new BigDecimal("1200")));

        assertThat(response.lineStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void create_lineStatusIsActive_whenAmountEqualsLimit_noViolationBecauseEvaluatorUsesStrictlyGreaterThan() {
        // The evaluator's amount > limit (not >=) semantics are DefaultPolicyEvaluatorTest's concern;
        // here we only confirm that an empty violation list (as it would return for amount == limit)
        // leaves the line item ACTIVE.
        stubOwnerAndReport();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory("TRAVEL")));
        when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(currency));
        when(expenseLineItemRepository.save(any(ExpenseLineItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(policyEvaluator.evaluate(any())).thenReturn(List.of());

        ExpenseLineItemResponse response = expenseLineItemService.create(reportId, requestWithAmount(new BigDecimal("1500")));

        assertThat(response.lineStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void create_setsLineStatusBlocked_whenBlockViolationDetected() {
        stubOwnerAndReport();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory("TRAVEL")));
        when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(currency));
        when(expenseLineItemRepository.save(any(ExpenseLineItem.class))).thenAnswer(inv -> inv.getArgument(0));
        PolicyViolation blocking = amountLimitViolation(PolicyEnforcementType.BLOCK);
        when(policyEvaluator.evaluate(any())).thenReturn(List.of(blocking));
        when(policyViolationRepository.findByLineItem_LineItemId(any())).thenReturn(List.of(blocking));

        ExpenseLineItemResponse response = expenseLineItemService.create(reportId, requestWithAmount(new BigDecimal("1600")));

        assertThat(response.lineStatus()).isEqualTo("BLOCKED");
        assertThat(response.policyWarnings()).hasSize(1);
        verify(policyViolationRepository).saveAll(List.of(blocking));
        // BLOCK never rejects the save - the line item and its violation are both still persisted.
        verify(expenseLineItemRepository).save(any(ExpenseLineItem.class));
    }

    @Test
    void create_lineStatusRemainsActive_whenOnlyWarnViolationDetected() {
        stubOwnerAndReport();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory("TRAVEL")));
        when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(currency));
        when(expenseLineItemRepository.save(any(ExpenseLineItem.class))).thenAnswer(inv -> inv.getArgument(0));
        PolicyViolation warning = amountLimitViolation(PolicyEnforcementType.WARN);
        when(policyEvaluator.evaluate(any())).thenReturn(List.of(warning));
        when(policyViolationRepository.findByLineItem_LineItemId(any())).thenReturn(List.of(warning));

        ExpenseLineItemResponse response = expenseLineItemService.create(reportId, requestWithAmount(new BigDecimal("1600")));

        assertThat(response.lineStatus()).isEqualTo("ACTIVE");
        assertThat(response.policyWarnings()).hasSize(1);
    }

    @Test
    void update_revertsLineStatusFromBlockedToActive_whenBlockingViolationIsResolved() {
        UUID lineItemId = UUID.randomUUID();
        ExpenseCategory category = activeCategory("TRAVEL");
        ExpenseLineItem existing = ExpenseLineItem.builder().lineItemId(lineItemId).report(draftReport)
                .category(category).amount(new BigDecimal("1600")).currency(currency).lineStatus("BLOCKED").build();
        PolicyViolation staleBlocking = amountLimitViolation(PolicyEnforcementType.BLOCK);

        stubOwnerAndReport();
        when(expenseLineItemRepository.findByLineItemIdAndReport_ReportId(lineItemId, reportId))
                .thenReturn(Optional.of(existing));
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
        when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(currency));
        when(expenseLineItemRepository.save(any(ExpenseLineItem.class))).thenAnswer(inv -> inv.getArgument(0));
        // First call is refreshPolicyViolations()'s "existing" lookup (before delete); the second
        // is toResponse()'s post-recompute read — a real repository would return the recomputed
        // (now empty) set there, which this two-value stub mirrors.
        when(policyViolationRepository.findByLineItem_LineItemId(lineItemId)).thenReturn(List.of(staleBlocking), List.of());
        // The employee lowered the amount to 1200 - no longer breaches the limit.
        when(policyEvaluator.evaluate(any())).thenReturn(List.of());

        ExpenseLineItemResponse response = expenseLineItemService.update(reportId, lineItemId, requestWithAmount(new BigDecimal("1200")));

        assertThat(response.lineStatus()).isEqualTo("ACTIVE");
        assertThat(response.policyWarnings()).isEmpty();
        verify(policyViolationRepository).deleteAll(List.of(staleBlocking));
    }

    // ---- EP02-S4: VAT/GST ----

    @Test
    void create_calculatesNetAmount_asTotalMinusTax() {
        stubOwnerAndReport();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory("TRAVEL")));
        when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(currency));
        when(expenseLineItemRepository.save(any(ExpenseLineItem.class))).thenAnswer(inv -> inv.getArgument(0));

        ExpenseLineItemRequest withTax = new ExpenseLineItemRequest(categoryId, LocalDate.now().minusDays(1), "Uber",
                "Client meeting", new BigDecimal("100.00"), currencyId, new BigDecimal("18.00"), null, null, false);

        ExpenseLineItemResponse response = expenseLineItemService.create(reportId, withTax);

        assertThat(response.taxAmount()).isEqualByComparingTo("18.00");
        assertThat(response.netAmount()).isEqualByComparingTo("82.00");
    }

    @Test
    void create_treatsNullTaxAsZero_forNetAmount() {
        stubOwnerAndReport();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory("TRAVEL")));
        when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(currency));
        when(expenseLineItemRepository.save(any(ExpenseLineItem.class))).thenAnswer(inv -> inv.getArgument(0));

        ExpenseLineItemResponse response = expenseLineItemService.create(reportId, validRequest());

        assertThat(response.netAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void create_throwsIllegalArgumentException_whenTaxAmountNegative() {
        stubOwnerAndReport();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory("TRAVEL")));

        ExpenseLineItemRequest negativeTax = new ExpenseLineItemRequest(categoryId, LocalDate.now().minusDays(1), "Uber",
                "desc", new BigDecimal("100.00"), currencyId, new BigDecimal("-5.00"), null, null, false);

        assertThatThrownBy(() -> expenseLineItemService.create(reportId, negativeTax))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");

        verify(expenseLineItemRepository, never()).save(any());
    }

    @Test
    void create_throwsIllegalArgumentException_whenTaxAmountExceedsTotalAmount() {
        stubOwnerAndReport();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory("TRAVEL")));

        ExpenseLineItemRequest excessiveTax = new ExpenseLineItemRequest(categoryId, LocalDate.now().minusDays(1), "Uber",
                "desc", new BigDecimal("100.00"), currencyId, new BigDecimal("150.00"), null, null, false);

        assertThatThrownBy(() -> expenseLineItemService.create(reportId, excessiveTax))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceed");

        verify(expenseLineItemRepository, never()).save(any());
    }

    // ---- EP02-S3: multi-currency â€” every line item converts into the Organization Base Currency
    // (INR, per setUp()'s shared `currency`), regardless of what currency the report itself is in.
    // Each test below deliberately sets the report's currency to something else (GBP) to prove the
    // report currency never influences the conversion target.

    private Currency reportDisplayCurrencyDifferentFromBase() {
        Currency gbp = Currency.builder().currencyId(UUID.randomUUID()).currencyCode("GBP").status("ACTIVE").build();
        draftReport.setCurrency(gbp);
        return gbp;
    }

    @Test
    void create_convertsUsdToOrgBaseCurrency_regardlessOfReportCurrency() {
        reportDisplayCurrencyDifferentFromBase();
        UUID usdId = UUID.randomUUID();
        Currency usd = Currency.builder().currencyId(usdId).currencyCode("USD").status("ACTIVE").build();

        stubOwnerAndReport();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory("TRAVEL")));
        when(currencyRepository.findById(usdId)).thenReturn(Optional.of(usd));
        when(exchangeRateService.getHistoricalRate(eq(usdId), eq(currencyId), any()))
                .thenReturn(new ExchangeRateResponse(UUID.randomUUID(), usdId, "USD", currencyId, "INR",
                        new BigDecimal("95.969290"), LocalDate.now().minusDays(1), "SCHEDULED_REFRESH", null, null, null));
        when(expenseLineItemRepository.save(any(ExpenseLineItem.class))).thenAnswer(inv -> inv.getArgument(0));

        ExpenseLineItemRequest usdRequest = new ExpenseLineItemRequest(categoryId, LocalDate.now().minusDays(1), "Uber",
                "desc", new BigDecimal("100.00"), usdId, null, null, null, false);

        ExpenseLineItemResponse response = expenseLineItemService.create(reportId, usdRequest);

        assertThat(response.currencyCode()).isEqualTo("USD");
        assertThat(response.exchangeRate()).isEqualByComparingTo("95.969290");
        assertThat(response.baseAmount()).isEqualByComparingTo("9596.9290");
        assertThat(response.baseCurrencyCode()).isEqualTo("INR");
    }

    @Test
    void create_convertsEurToOrgBaseCurrency_regardlessOfReportCurrency() {
        reportDisplayCurrencyDifferentFromBase();
        UUID eurId = UUID.randomUUID();
        Currency eur = Currency.builder().currencyId(eurId).currencyCode("EUR").status("ACTIVE").build();

        stubOwnerAndReport();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory("TRAVEL")));
        when(currencyRepository.findById(eurId)).thenReturn(Optional.of(eur));
        when(exchangeRateService.getHistoricalRate(eq(eurId), eq(currencyId), any()))
                .thenReturn(new ExchangeRateResponse(UUID.randomUUID(), eurId, "EUR", currencyId, "INR",
                        new BigDecimal("109.098844"), LocalDate.now().minusDays(1), "SCHEDULED_REFRESH", null, null, null));
        when(expenseLineItemRepository.save(any(ExpenseLineItem.class))).thenAnswer(inv -> inv.getArgument(0));

        ExpenseLineItemRequest eurRequest = new ExpenseLineItemRequest(categoryId, LocalDate.now().minusDays(1), "Uber",
                "desc", new BigDecimal("100.00"), eurId, null, null, null, false);

        ExpenseLineItemResponse response = expenseLineItemService.create(reportId, eurRequest);

        assertThat(response.currencyCode()).isEqualTo("EUR");
        assertThat(response.exchangeRate()).isEqualByComparingTo("109.098844");
        assertThat(response.baseAmount()).isEqualByComparingTo("10909.8844");
        assertThat(response.baseCurrencyCode()).isEqualTo("INR");
    }

    /** exchangeRate = 1 only because the line item's own currency equals the Organization Base Currency â€” never because it happens to equal the report's (GBP) currency. */
    @Test
    void create_keepsExchangeRateAtOne_whenLineItemCurrencyEqualsOrgBaseCurrency() {
        reportDisplayCurrencyDifferentFromBase();

        stubOwnerAndReport();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory("TRAVEL")));
        when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(currency));
        when(expenseLineItemRepository.save(any(ExpenseLineItem.class))).thenAnswer(inv -> inv.getArgument(0));

        ExpenseLineItemResponse response = expenseLineItemService.create(reportId, validRequest());

        assertThat(response.currencyCode()).isEqualTo("INR");
        assertThat(response.exchangeRate()).isEqualByComparingTo("1");
        assertThat(response.baseAmount()).isEqualByComparingTo("100.00");
        assertThat(response.baseCurrencyCode()).isEqualTo("INR");
        verify(exchangeRateService, never()).getHistoricalRate(any(), any(), any());
    }

    /** Two line items in different transaction currencies on the same (GBP-denominated) report both land in INR â€” proving report totals sum correctly across multiple currencies. */
    @Test
    void create_multipleCurrenciesOnSameReport_allConvertToOrgBaseCurrency() {
        reportDisplayCurrencyDifferentFromBase();

        UUID usdId = UUID.randomUUID();
        Currency usd = Currency.builder().currencyId(usdId).currencyCode("USD").status("ACTIVE").build();
        UUID eurId = UUID.randomUUID();
        Currency eur = Currency.builder().currencyId(eurId).currencyCode("EUR").status("ACTIVE").build();

        stubOwnerAndReport();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory("TRAVEL")));
        when(currencyRepository.findById(usdId)).thenReturn(Optional.of(usd));
        when(currencyRepository.findById(eurId)).thenReturn(Optional.of(eur));
        when(exchangeRateService.getHistoricalRate(eq(usdId), eq(currencyId), any()))
                .thenReturn(new ExchangeRateResponse(UUID.randomUUID(), usdId, "USD", currencyId, "INR",
                        new BigDecimal("95.969290"), LocalDate.now().minusDays(1), "SCHEDULED_REFRESH", null, null, null));
        when(exchangeRateService.getHistoricalRate(eq(eurId), eq(currencyId), any()))
                .thenReturn(new ExchangeRateResponse(UUID.randomUUID(), eurId, "EUR", currencyId, "INR",
                        new BigDecimal("110.00"), LocalDate.now().minusDays(1), "SCHEDULED_REFRESH", null, null, null));
        when(expenseLineItemRepository.save(any(ExpenseLineItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(expenseLineItemRepository.sumBaseAmountByReportId(reportId)).thenReturn(new BigDecimal("15096.9290"));

        ExpenseLineItemRequest usdRequest = new ExpenseLineItemRequest(categoryId, LocalDate.now().minusDays(1), "Uber",
                "desc", new BigDecimal("100.00"), usdId, null, null, null, false);
        ExpenseLineItemRequest eurRequest = new ExpenseLineItemRequest(categoryId, LocalDate.now().minusDays(1), "Hotel",
                "desc", new BigDecimal("50.00"), eurId, null, null, null, false);

        ExpenseLineItemResponse usdResponse = expenseLineItemService.create(reportId, usdRequest);
        ExpenseLineItemResponse eurResponse = expenseLineItemService.create(reportId, eurRequest);

        assertThat(usdResponse.baseAmount()).isEqualByComparingTo("9596.9290");
        assertThat(eurResponse.baseAmount()).isEqualByComparingTo("5500.0000");
        // Report total is a repository-computed SUM(baseAmount) â€” asserted against the stubbed value here;
        // the DB-level correctness of "SUM(baseAmount) in INR" is exercised structurally by the query itself
        // only ever summing the baseAmount column, never amount.
        assertThat(draftReport.getTotalAmount()).isEqualByComparingTo("15096.9290");
    }

    @Test
    void create_throwsBusinessRuleViolation_whenExchangeRateUnavailable() {
        reportDisplayCurrencyDifferentFromBase();
        UUID foreignCurrencyId = UUID.randomUUID();
        Currency foreignCurrency = Currency.builder().currencyId(foreignCurrencyId).currencyCode("EUR").status("ACTIVE").build();

        stubOwnerAndReport();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory("TRAVEL")));
        when(currencyRepository.findById(foreignCurrencyId)).thenReturn(Optional.of(foreignCurrency));
        when(exchangeRateService.getHistoricalRate(eq(foreignCurrencyId), eq(currencyId), any()))
                .thenThrow(new ResourceNotFoundException("No exchange rate is available for this currency pair"));

        ExpenseLineItemRequest foreignRequest = new ExpenseLineItemRequest(categoryId, LocalDate.now().minusDays(1), "Uber",
                "desc", new BigDecimal("100.00"), foreignCurrencyId, null, null, null, false);

        assertThatThrownBy(() -> expenseLineItemService.create(reportId, foreignRequest))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("exchange rate");

        verify(expenseLineItemRepository, never()).save(any());
    }

    @Test
    void create_throwsIllegalArgumentException_withActionableMessage_whenCurrencyNotEnabled() {
        UUID inactiveCurrencyId = UUID.randomUUID();
        Currency inactiveCurrency = Currency.builder().currencyId(inactiveCurrencyId).currencyCode("AED").status("INACTIVE").build();

        stubOwnerAndReport();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory("TRAVEL")));
        when(currencyRepository.findById(inactiveCurrencyId)).thenReturn(Optional.of(inactiveCurrency));

        ExpenseLineItemRequest request = new ExpenseLineItemRequest(categoryId, LocalDate.now().minusDays(1), "Uber",
                "desc", new BigDecimal("100.00"), inactiveCurrencyId, null, null, null, false);

        assertThatThrownBy(() -> expenseLineItemService.create(reportId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Administrator");

        verify(expenseLineItemRepository, never()).save(any());
    }

    @Test
    void create_throwsIllegalStateException_whenOrganizationBaseCurrencyNotConfigured() {
        when(currencyRepository.findByCurrencyCodeIgnoreCase("INR")).thenReturn(Optional.empty());
        stubOwnerAndReport();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory("TRAVEL")));
        when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(currency));

        assertThatThrownBy(() -> expenseLineItemService.create(reportId, validRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Organization base currency");

        verify(expenseLineItemRepository, never()).save(any());
    }

    @Test
    void create_recalculatesReportTotalInBaseCurrency() {
        stubOwnerAndReport();
        when(expenseCategoryRepository.findById(categoryId)).thenReturn(Optional.of(activeCategory("TRAVEL")));
        when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(currency));
        when(expenseLineItemRepository.save(any(ExpenseLineItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(expenseLineItemRepository.sumBaseAmountByReportId(reportId)).thenReturn(new BigDecimal("100.00"));

        expenseLineItemService.create(reportId, validRequest());

        verify(expenseReportRepository).save(draftReport);
        assertThat(draftReport.getTotalAmount()).isEqualByComparingTo("100.00");
    }
}
