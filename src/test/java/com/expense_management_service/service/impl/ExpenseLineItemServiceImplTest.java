package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ExpenseLineItemRequest;
import com.expense_management_service.dto.response.ExpenseLineItemResponse;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseCategory;
import com.expense_management_service.entity.ExpenseLineItem;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.mapper.ExpenseLineItemMapper;
import com.expense_management_service.repository.CostCenterRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExpenseCategoryRepository;
import com.expense_management_service.repository.ExpenseLineItemRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.ProjectCacheRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.ExchangeRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
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

    private ExpenseLineItemServiceImpl expenseLineItemService;

    private final String employeeId = "5100014";
    private UUID reportId;
    private UUID categoryId;
    private UUID currencyId;
    private ExpenseReport draftReport;
    private Currency currency;

    @BeforeEach
    void setUp() {
        expenseLineItemService = new ExpenseLineItemServiceImpl(
                expenseLineItemRepository, expenseReportRepository, expenseCategoryRepository, currencyRepository,
                costCenterRepository, projectCacheRepository, exchangeRateService, currentUserService,
                new ExpenseLineItemMapper());

        reportId = UUID.randomUUID();
        categoryId = UUID.randomUUID();
        currencyId = UUID.randomUUID();
        currency = Currency.builder().currencyId(currencyId).currencyCode("USD").status("ACTIVE").build();
        draftReport = ExpenseReport.builder().reportId(reportId).employeeId(employeeId)
                .reportStatus("DRAFT").currency(currency).build();
    }

    private CurrentUser employeeCaller() {
        return new CurrentUser(UUID.randomUUID(), employeeId, "jordan@example.com", "Jordan", List.of("EMPLOYEE"), List.of());
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
        draftReport.setReportStatus("SUBMITTED");
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
        draftReport.setReportStatus("SUBMITTED");
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
}
