package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.ResourceNotFoundException;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.mapper.CashAdvanceMapper;
import com.expense_management_service.repository.CashAdvanceAdjustmentRepository;
import com.expense_management_service.repository.CashAdvanceRepaymentRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.repository.CostCenterRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.security.RoleConstants;
import com.expense_management_service.service.DelegationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Access control for cash advance reads. Every case goes through the same service methods the REST endpoints
 * call, so a request made directly to the API, not through the UI, gets the same answer.
 */
@ExtendWith(MockitoExtension.class)
class CashAdvanceAccessTest {

    private static final String OWNER = "5100014";        // the employee who requested the advance
    private static final String OTHER = "5100099";        // another employee
    private static final String MANAGER = "5100023";      // the advance's manager

    @Mock private CashAdvanceRepository cashAdvanceRepository;
    @Mock private CashAdvanceRepaymentRepository cashAdvanceRepaymentRepository;
    @Mock private CashAdvanceAdjustmentRepository cashAdvanceAdjustmentRepository;
    @Mock private CurrentUserService currentUserService;
    @Mock private DelegationService delegationService;
    @Mock private EmployeeCacheRepository employeeCacheRepository;
    @Mock private CostCenterRepository costCenterRepository;
    @Mock private CashAdvanceMapper cashAdvanceMapper;
    @Mock private CashAdvanceUtilizationCalculator cashAdvanceUtilizationCalculator;

    @InjectMocks private CashAdvanceServiceImpl service;

    private CashAdvance advance;
    private UUID advanceId;

    @BeforeEach
    void setUp() {
        advanceId = UUID.randomUUID();
        advance = CashAdvance.builder()
                .advanceId(advanceId)
                .employeeId(OWNER)
                .managerId(MANAGER)
                .amount(new BigDecimal("8000.00"))
                .outstandingBalance(new BigDecimal("8000.00"))
                .status("IN_PROGRESS")
                .build();
        lenient().when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));
        lenient().when(delegationService.resolveApproverIdsActingFor(anyString()))
                .thenAnswer(inv -> Set.of(inv.getArgument(0, String.class)));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    /** Signs in as an employee (no privileged role). */
    private void signInAs(String employeeId) {
        signInAs(employeeId, List.of());
    }

    private void signInAs(String employeeId, List<GrantedAuthority> authorities) {
        lenient().when(currentUserService.getEmployeeId()).thenReturn(employeeId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(employeeId, "n/a", authorities));
    }

    private void signInAsFinance(String employeeId) {
        signInAs(employeeId, List.of(new SimpleGrantedAuthority(RoleConstants.ROLE_FINANCE)));
    }

    // ---------------------------------------------------------------- detail

    @Test
    void employee_canViewTheirOwnAdvance() {
        signInAs(OWNER);

        service.getById(advanceId);

        verify(cashAdvanceMapper).toResponse(advance);
    }

    @Test
    void employee_cannotViewAnotherEmployeesAdvance_evenByDirectId() {
        signInAs(OTHER);

        assertThrows(ResourceNotFoundException.class, () -> service.getById(advanceId));
        verify(cashAdvanceMapper, never()).toResponse(any());
    }

    @Test
    void notSignedIn_cannotViewAnyAdvance() {
        when(currentUserService.getEmployeeId()).thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () -> service.getById(advanceId));
    }

    @Test
    void manager_canViewAnAdvanceTheyApprove() {
        signInAs(MANAGER);

        service.getById(advanceId);

        verify(cashAdvanceMapper).toResponse(advance);
    }

    @Test
    void delegateActingForTheManager_canViewTheAdvance() {
        String delegate = "5100050";
        signInAs(delegate);
        when(delegationService.resolveApproverIdsActingFor(delegate)).thenReturn(Set.of(delegate, MANAGER));

        service.getById(advanceId);

        verify(cashAdvanceMapper).toResponse(advance);
    }

    @Test
    void unrelatedApprover_cannotViewTheAdvance() {
        String unrelated = "5100077";
        signInAs(unrelated);

        assertThrows(ResourceNotFoundException.class, () -> service.getById(advanceId));
    }

    @Test
    void finance_canViewAnyAdvance() {
        signInAsFinance("5100009");

        service.getById(advanceId);

        verify(cashAdvanceMapper).toResponse(advance);
    }

    // ------------------------------------------------------------ settlement

    @Test
    void employee_cannotReadAnotherEmployeesSettlement() {
        signInAs(OTHER);

        assertThrows(ResourceNotFoundException.class, () -> service.getSettlement(advanceId));
        verify(cashAdvanceAdjustmentRepository, never()).findByCashAdvance_AdvanceId(any());
        verify(cashAdvanceRepaymentRepository, never()).findByCashAdvance_AdvanceId(any());
    }

    @Test
    void employee_cannotReadAnotherEmployeesUtilization() {
        signInAs(OTHER);

        assertThrows(ResourceNotFoundException.class, () -> service.getUtilization(advanceId));
        verify(cashAdvanceUtilizationCalculator, never()).calculate(any());
    }

    @Test
    void owner_canReadTheirOwnUtilization() {
        signInAs(OWNER);

        service.getUtilization(advanceId);

        verify(cashAdvanceUtilizationCalculator).calculate(advance);
    }

    // ------------------------------------------------------------------ list

    @Test
    void employeeList_isForcedToTheirOwnAdvances_whateverEmployeeIdIsPassed() {
        signInAs(OWNER);
        Pageable page = PageRequest.of(0, 20);
        when(cashAdvanceRepository.findByEmployeeId(eq(OWNER), any())).thenReturn(Page.empty());

        service.getFiltered(OTHER, null, page);

        verify(cashAdvanceRepository).findByEmployeeId(eq(OWNER), any());
        verify(cashAdvanceRepository, never()).findByEmployeeId(eq(OTHER), any());
        verify(cashAdvanceRepository, never()).findAll();
    }

    @Test
    void employeeList_withNoEmployeeIdAndNoStatus_doesNotReturnEveryAdvance() {
        signInAs(OWNER);
        Pageable page = PageRequest.of(0, 20);
        when(cashAdvanceRepository.findByEmployeeId(eq(OWNER), any())).thenReturn(Page.empty());

        service.getFiltered(null, null, page);

        verify(cashAdvanceRepository, never()).findAll();
        verify(cashAdvanceRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void financeList_canStillFilterByAnyEmployee() {
        signInAsFinance("5100009");
        Pageable page = PageRequest.of(0, 20);
        when(cashAdvanceRepository.findByEmployeeId(eq(OTHER), any())).thenReturn(new PageImpl<>(List.of()));

        service.getFiltered(OTHER, null, page);

        verify(cashAdvanceRepository).findByEmployeeId(eq(OTHER), any());
    }
}
