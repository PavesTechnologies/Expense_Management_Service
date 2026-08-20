package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.common.exception.DuplicateResourceException;
import com.expense_management_service.common.exception.EmployeeInactiveException;
import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.dto.request.ExpenseReportRequest;
import com.expense_management_service.dto.response.ExpenseReportResponse;
import com.expense_management_service.entity.CostCenter;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.ExpenseReport;
import com.expense_management_service.enums.ReportStatus;
import com.expense_management_service.integration.ums.UmsClient;
import com.expense_management_service.integration.ums.dto.UmsUserResponse;
import com.expense_management_service.mapper.ExpenseReportMapper;
import com.expense_management_service.repository.CostCenterRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.ExpenseReportRepository;
import com.expense_management_service.repository.PolicyViolationRepository;
import com.expense_management_service.security.CurrentUser;
import com.expense_management_service.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.Year;
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
class ExpenseReportServiceImplTest {

    @Mock
    private ExpenseReportRepository expenseReportRepository;
    @Mock
    private CostCenterRepository costCenterRepository;
    @Mock
    private CurrencyRepository currencyRepository;
    @Mock
    private UmsClient umsClient;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private PolicyViolationRepository policyViolationRepository;

    private ExpenseReportServiceImpl expenseReportService;

    private final UUID employeeUuid = UUID.randomUUID();
    private final String employeeId = "5100014";
    private UUID costCenterId;
    private UUID currencyId;
    private String fiscalYear;

    @BeforeEach
    void setUp() {
        expenseReportService = new ExpenseReportServiceImpl(
                expenseReportRepository, costCenterRepository, currencyRepository, umsClient,
                currentUserService, new ExpenseReportMapper(), policyViolationRepository);
        ReflectionTestUtils.setField(expenseReportService, "businessPurposeMinLength", 10);

        costCenterId = UUID.randomUUID();
        currencyId = UUID.randomUUID();
        fiscalYear = String.valueOf(Year.now().getValue());
    }

    private CurrentUser employeeCaller() {
        return new CurrentUser(employeeUuid, employeeId, "jordan@example.com", "Jordan", List.of("GENERAL"), List.of());
    }

    private CurrentUser adminCaller() {
        return new CurrentUser(UUID.randomUUID(), "9999999", "admin@example.com", "Admin", List.of("ADMIN"), List.of());
    }

    private ExpenseReportRequest validRequest() {
        return new ExpenseReportRequest("Client visit - Q1", "Client visit to discuss renewal terms", costCenterId, currencyId);
    }

    private void stubActiveEmployee(boolean active) {
        when(umsClient.getAllUsers()).thenReturn(List.of(
                new UmsUserResponse(employeeUuid, 5100014L, "Jordan", "Smith", "jordan@example.com", active)));
    }

    private void stubActiveCostCenterAndCurrency() {
        CostCenter costCenter = CostCenter.builder().costCenterId(costCenterId).costCenterCode("CC-100").status("ACTIVE").build();
        Currency currency = Currency.builder().currencyId(currencyId).currencyCode("USD").status("ACTIVE").build();
        lenient().when(costCenterRepository.findById(costCenterId)).thenReturn(Optional.of(costCenter));
        lenient().when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(currency));
    }

    @Test
    void create_savesDraftReport_ownedByCaller_whenValid() {
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        stubActiveEmployee(true);
        stubActiveCostCenterAndCurrency();
        when(expenseReportRepository.findByEmployeeIdAndFiscalYearAndTitleIgnoreCase(employeeId, fiscalYear, "Client visit - Q1"))
                .thenReturn(Optional.empty());
        when(expenseReportRepository.save(any(ExpenseReport.class))).thenAnswer(inv -> {
            ExpenseReport saved = inv.getArgument(0);
            saved.setReportId(UUID.randomUUID());
            return saved;
        });

        ExpenseReportResponse response = expenseReportService.create(validRequest());

        assertThat(response.employeeId()).isEqualTo(employeeId);
        assertThat(response.reportStatus()).isEqualTo("DRAFT");
        assertThat(response.fiscalYear()).isEqualTo(fiscalYear);
        assertThat(response.reportNumber()).startsWith("EXP-" + fiscalYear);
        assertThat(response.editable()).isTrue();
        assertThat(response.deletable()).isTrue();
    }

    @Test
    void create_throwsEmployeeInactiveException_whenEmployeeInactiveInUms() {
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        stubActiveEmployee(false);

        assertThatThrownBy(() -> expenseReportService.create(validRequest()))
                .isInstanceOf(EmployeeInactiveException.class);

        verify(expenseReportRepository, never()).save(any());
    }

    @Test
    void create_throwsIllegalArgumentException_whenBusinessPurposeTooShort() {
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        stubActiveEmployee(true);

        ExpenseReportRequest request = new ExpenseReportRequest("Title", "too short", costCenterId, currencyId);

        assertThatThrownBy(() -> expenseReportService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10 characters");

        verify(expenseReportRepository, never()).save(any());
    }

    @Test
    void create_throwsDuplicateResourceException_whenTitleAlreadyExistsForEmployeeAndFiscalYear() {
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        stubActiveEmployee(true);
        ExpenseReport existing = ExpenseReport.builder().reportId(UUID.randomUUID()).build();
        when(expenseReportRepository.findByEmployeeIdAndFiscalYearAndTitleIgnoreCase(employeeId, fiscalYear, "Client visit - Q1"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> expenseReportService.create(validRequest()))
                .isInstanceOf(DuplicateResourceException.class);

        verify(expenseReportRepository, never()).save(any());
    }

    @Test
    void create_throwsIllegalArgumentException_whenCostCenterIsInactive() {
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        stubActiveEmployee(true);
        when(expenseReportRepository.findByEmployeeIdAndFiscalYearAndTitleIgnoreCase(employeeId, fiscalYear, "Client visit - Q1"))
                .thenReturn(Optional.empty());
        CostCenter inactive = CostCenter.builder().costCenterId(costCenterId).costCenterCode("CC-100").status("INACTIVE").build();
        when(costCenterRepository.findById(costCenterId)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> expenseReportService.create(validRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not Active");

        verify(expenseReportRepository, never()).save(any());
    }

    @Test
    void update_throwsAccessDenied_whenCallerIsNotOwner() {
        UUID reportId = UUID.randomUUID();
        ExpenseReport existing = ExpenseReport.builder().reportId(reportId).employeeId("someone-else")
                .reportStatus(ReportStatus.DRAFT).fiscalYear(fiscalYear).title("Old title").build();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(existing));
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());

        assertThatThrownBy(() -> expenseReportService.update(reportId, validRequest()))
                .isInstanceOf(AccessDeniedException.class);

        verify(expenseReportRepository, never()).save(any());
    }

    @Test
    void update_allowsAdmin_toEditAnyReport() {
        UUID reportId = UUID.randomUUID();
        ExpenseReport existing = ExpenseReport.builder().reportId(reportId).employeeId("someone-else")
                .reportStatus(ReportStatus.DRAFT).fiscalYear(fiscalYear).title("Old title").build();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(existing));
        when(currentUserService.getCurrentUser()).thenReturn(adminCaller());
        stubActiveCostCenterAndCurrency();
        when(expenseReportRepository.findByEmployeeIdAndFiscalYearAndTitleIgnoreCase("someone-else", fiscalYear, "Client visit - Q1"))
                .thenReturn(Optional.empty());
        when(expenseReportRepository.save(any(ExpenseReport.class))).thenAnswer(inv -> inv.getArgument(0));

        ExpenseReportResponse response = expenseReportService.update(reportId, validRequest());

        assertThat(response.title()).isEqualTo("Client visit - Q1");
    }

    @Test
    void update_throwsBusinessRuleViolation_whenReportNotEditable() {
        UUID reportId = UUID.randomUUID();
        ExpenseReport existing = ExpenseReport.builder().reportId(reportId).employeeId(employeeId)
                .reportStatus(ReportStatus.PENDING_APPROVAL).fiscalYear(fiscalYear).title("Old title").build();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(existing));
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());

        assertThatThrownBy(() -> expenseReportService.update(reportId, validRequest()))
                .isInstanceOf(BusinessRuleViolationException.class);

        verify(expenseReportRepository, never()).save(any());
    }

    @Test
    void getById_throwsAccessDenied_whenEmployeeRequestsSomeoneElsesReport() {
        UUID reportId = UUID.randomUUID();
        ExpenseReport existing = ExpenseReport.builder().reportId(reportId).employeeId("someone-else")
                .reportStatus(ReportStatus.DRAFT).fiscalYear(fiscalYear).build();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(existing));
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());

        assertThatThrownBy(() -> expenseReportService.getById(reportId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getById_allowsApExecutive_toViewSomeoneElsesReport() {
        UUID reportId = UUID.randomUUID();
        ExpenseReport existing = ExpenseReport.builder().reportId(reportId).employeeId("someone-else")
                .reportStatus(ReportStatus.APPROVED).fiscalYear(fiscalYear).build();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(existing));
        when(currentUserService.getCurrentUser()).thenReturn(
                new CurrentUser(UUID.randomUUID(), "ap-user", "ap@example.com", "AP", List.of("AP_EXECUTIVE"), List.of()));

        ExpenseReportResponse response = expenseReportService.getById(reportId);

        assertThat(response.reportId()).isEqualTo(reportId);
    }

    @Test
    void getById_allowsFinanceExecutive_toViewSomeoneElsesReport() {
        UUID reportId = UUID.randomUUID();
        ExpenseReport existing = ExpenseReport.builder().reportId(reportId).employeeId("someone-else")
                .reportStatus(ReportStatus.PENDING_APPROVAL).fiscalYear(fiscalYear).build();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(existing));
        when(currentUserService.getCurrentUser()).thenReturn(
                new CurrentUser(UUID.randomUUID(), "finance-user", "finance@example.com", "Finance", List.of("FINANCE_EXECUTIVE"), List.of()));

        ExpenseReportResponse response = expenseReportService.getById(reportId);

        assertThat(response.reportId()).isEqualTo(reportId);
    }

    @Test
    void getById_throwsResourceNotFoundException_whenMissing() {
        UUID reportId = UUID.randomUUID();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> expenseReportService.getById(reportId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getById_includesPolicyWarningCounts() {
        UUID reportId = UUID.randomUUID();
        ExpenseReport existing = ExpenseReport.builder().reportId(reportId).employeeId(employeeId)
                .reportStatus(ReportStatus.DRAFT).fiscalYear(fiscalYear).build();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(existing));
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());

        var justified = com.expense_management_service.entity.PolicyViolation.builder()
                .violationId(UUID.randomUUID()).justification("explained").build();
        var unjustified = com.expense_management_service.entity.PolicyViolation.builder()
                .violationId(UUID.randomUUID()).build();
        when(policyViolationRepository.findByLineItem_Report_ReportId(reportId)).thenReturn(List.of(justified, unjustified));

        ExpenseReportResponse response = expenseReportService.getById(reportId);

        assertThat(response.policyWarningCount()).isEqualTo(2);
        assertThat(response.policyUnjustifiedCount()).isEqualTo(1);
    }

    @Test
    void getAll_scopesToOwnReports_forEmployeeRole() {
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());
        ExpenseReport own = ExpenseReport.builder().reportId(UUID.randomUUID()).employeeId(employeeId)
                .reportStatus(ReportStatus.DRAFT).fiscalYear(fiscalYear).build();
        when(expenseReportRepository.findByEmployeeId(employeeId)).thenReturn(List.of(own));

        List<ExpenseReportResponse> result = expenseReportService.getAll();

        assertThat(result).hasSize(1);
        verify(expenseReportRepository, never()).findAll();
    }

    @Test
    void getAll_returnsEveryReport_forFinanceRole() {
        CurrentUser finance = new CurrentUser(UUID.randomUUID(), "financeUser", "f@example.com", "Finance", List.of("FINANCE"), List.of());
        when(currentUserService.getCurrentUser()).thenReturn(finance);
        when(expenseReportRepository.findAll()).thenReturn(List.of(
                ExpenseReport.builder().reportId(UUID.randomUUID()).employeeId("a").reportStatus(ReportStatus.DRAFT).fiscalYear(fiscalYear).build(),
                ExpenseReport.builder().reportId(UUID.randomUUID()).employeeId("b").reportStatus(ReportStatus.PENDING_APPROVAL).fiscalYear(fiscalYear).build()));

        List<ExpenseReportResponse> result = expenseReportService.getAll();

        assertThat(result).hasSize(2);
        verify(expenseReportRepository, never()).findByEmployeeId(any());
    }

    @Test
    void delete_removesReport_whenOwnerAndDraft() {
        UUID reportId = UUID.randomUUID();
        ExpenseReport existing = ExpenseReport.builder().reportId(reportId).employeeId(employeeId).reportStatus(ReportStatus.DRAFT).build();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(existing));
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());

        expenseReportService.delete(reportId);

        verify(expenseReportRepository).delete(existing);
    }

    @Test
    void delete_throwsBusinessRuleViolation_whenReportAlreadySubmitted() {
        UUID reportId = UUID.randomUUID();
        ExpenseReport existing = ExpenseReport.builder().reportId(reportId).employeeId(employeeId).reportStatus(ReportStatus.PENDING_APPROVAL).build();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(existing));
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());

        assertThatThrownBy(() -> expenseReportService.delete(reportId))
                .isInstanceOf(BusinessRuleViolationException.class);

        verify(expenseReportRepository, never()).delete(any());
    }

    @Test
    void delete_throwsAccessDenied_whenCallerIsNotOwnerOrAdmin() {
        UUID reportId = UUID.randomUUID();
        ExpenseReport existing = ExpenseReport.builder().reportId(reportId).employeeId("someone-else").reportStatus(ReportStatus.DRAFT).build();
        when(expenseReportRepository.findById(reportId)).thenReturn(Optional.of(existing));
        when(currentUserService.getCurrentUser()).thenReturn(employeeCaller());

        assertThatThrownBy(() -> expenseReportService.delete(reportId))
                .isInstanceOf(AccessDeniedException.class);

        verify(expenseReportRepository, never()).delete(any());
    }
}
