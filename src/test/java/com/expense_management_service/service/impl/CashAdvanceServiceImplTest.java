package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.dto.request.CashAdvanceRepaymentRequest;
import com.expense_management_service.dto.request.CashAdvanceRequest;
import com.expense_management_service.dto.response.CashAdvanceRepaymentResponse;
import com.expense_management_service.dto.response.CashAdvanceResponse;
import com.expense_management_service.dto.response.CashAdvanceSettlementResponse;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.entity.CashAdvanceRepayment;
import com.expense_management_service.entity.Currency;
import com.expense_management_service.entity.EmployeeCache;
import com.expense_management_service.mapper.CashAdvanceAdjustmentMapper;
import com.expense_management_service.mapper.CashAdvanceMapper;
import com.expense_management_service.mapper.CashAdvanceRepaymentMapper;
import com.expense_management_service.repository.AuditLogRepository;
import com.expense_management_service.repository.CashAdvanceAdjustmentRepository;
import com.expense_management_service.repository.CashAdvanceRepaymentRepository;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.repository.CurrencyRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.security.CurrentUserService;
import com.expense_management_service.service.ApprovalEventPublisher;
import com.expense_management_service.service.DelegationService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.AfterEach;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CashAdvanceServiceImplTest {

    @Mock
    private CashAdvanceRepository cashAdvanceRepository;

    @Mock
    private CurrencyRepository currencyRepository;

    @Mock
    private SystemConfigurationRepository systemConfigurationRepository;

    @Mock
    private EmployeeCacheRepository employeeCacheRepository;

    @Mock
    private DelegationService delegationService;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private ApprovalEventPublisher approvalEventPublisher;

    @Mock
    private CashAdvanceRepaymentRepository cashAdvanceRepaymentRepository;

    @Mock
    private CashAdvanceAdjustmentRepository cashAdvanceAdjustmentRepository;

    @Spy
    private CashAdvanceMapper cashAdvanceMapper = new CashAdvanceMapper();

    @Spy
    private CashAdvanceRepaymentMapper cashAdvanceRepaymentMapper = new CashAdvanceRepaymentMapper();

    @Spy
    private CashAdvanceAdjustmentMapper cashAdvanceAdjustmentMapper = new CashAdvanceAdjustmentMapper();

    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private CashAdvanceServiceImpl cashAdvanceService;

    private Currency testCurrency;
    private UUID currencyId;
    private UUID advanceId;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void setUp() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "FINANCE001", null, List.of(new SimpleGrantedAuthority("ROLE_FINANCE_EXECUTIVE"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
        currencyId = UUID.randomUUID();
        advanceId = UUID.randomUUID();
        testCurrency = Currency.builder()
                .currencyId(currencyId)
                .currencyCode("USD")
                .currencyName("US Dollar")
                .symbol("$")
                .build();
    }

    @Test
    void create_shouldDefaultEmployeeIdAndStatusAndResolveManager() {
        CashAdvanceRequest request = new CashAdvanceRequest(
                null,
                new BigDecimal("500.00"),
                currencyId,
                null,
                "Travel expenses",
                null,
                LocalDate.now().plusDays(10),
                null
        );

        EmployeeCache employeeCache = EmployeeCache.builder()
                .employeeId("EMP001")
                .managerEmployeeId("MGR001")
                .build();

        when(currencyRepository.findById(currencyId)).thenReturn(Optional.of(testCurrency));
        when(currentUserService.getEmployeeId()).thenReturn("EMP001");
        when(employeeCacheRepository.findByEmployeeId("EMP001")).thenReturn(Optional.of(employeeCache));
        when(cashAdvanceRepository.save(any(CashAdvance.class))).thenAnswer(invocation -> {
            CashAdvance saved = invocation.getArgument(0);
            saved.setAdvanceId(advanceId);
            return saved;
        });

        CashAdvanceResponse response = cashAdvanceService.create(request);

        assertNotNull(response);
        assertEquals("EMP001", response.employeeId());
        assertEquals("MGR001", response.managerId());
        assertEquals("DRAFT", response.status());
        assertEquals(new BigDecimal("500.00"), response.amount());
        assertEquals(new BigDecimal("500.00"), response.outstandingBalance());
    }

    @Test
    void create_shouldRejectTodayOrPastDate() {
        CashAdvanceRequest todayRequest = new CashAdvanceRequest(
                null,
                new BigDecimal("500.00"),
                currencyId,
                null,
                "Travel expenses",
                null,
                LocalDate.now(),
                null
        );

        assertThrows(BusinessRuleViolationException.class, () -> cashAdvanceService.create(todayRequest));

        CashAdvanceRequest pastRequest = new CashAdvanceRequest(
                null,
                new BigDecimal("500.00"),
                currencyId,
                null,
                "Travel expenses",
                null,
                LocalDate.now().minusDays(1),
                null
        );

        assertThrows(BusinessRuleViolationException.class, () -> cashAdvanceService.create(pastRequest));
    }

    @Test
    void submit_shouldChangeStatusToSubmittedAndAssignManager() {
        CashAdvance advance = CashAdvance.builder()
                .advanceId(advanceId)
                .employeeId("EMP001")
                .amount(new BigDecimal("300.00"))
                .currency(testCurrency)
                .settlementDueDate(LocalDate.now().plusDays(7))
                .status("DRAFT")
                .build();

        EmployeeCache employeeCache = EmployeeCache.builder()
                .employeeId("EMP001")
                .managerEmployeeId("MGR001")
                .build();

        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));
        when(employeeCacheRepository.findByEmployeeId("EMP001")).thenReturn(Optional.of(employeeCache));
        when(cashAdvanceRepository.save(any(CashAdvance.class))).thenAnswer(inv -> inv.getArgument(0));

        CashAdvanceResponse response = cashAdvanceService.submit(advanceId);

        assertEquals("SUBMITTED", response.status());
        assertEquals("MGR001", response.managerId());
    }

    @Test
    void approve_shouldChangeStatusToPendingFinanceApprovalWhenManagerApproves() {
        CashAdvance advance = CashAdvance.builder()
                .advanceId(advanceId)
                .employeeId("EMP001")
                .managerId("MGR001")
                .status("SUBMITTED")
                .build();

        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));
        when(delegationService.canAct("MGR001", "MGR001")).thenReturn(true);
        when(cashAdvanceRepository.save(any(CashAdvance.class))).thenAnswer(inv -> inv.getArgument(0));

        CashAdvanceResponse response = cashAdvanceService.approve(advanceId, "MGR001");

        assertEquals("PENDING_FINANCE_APPROVAL", response.status());
    }

    @Test
    void approve_shouldChangeStatusToApprovedWhenFinanceApproves() {
        CashAdvance advance = CashAdvance.builder()
                .advanceId(advanceId)
                .employeeId("EMP001")
                .managerId("MGR001")
                .status("PENDING_FINANCE_APPROVAL")
                .build();

        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));
        when(cashAdvanceRepository.save(any(CashAdvance.class))).thenAnswer(inv -> inv.getArgument(0));

        CashAdvanceResponse response = cashAdvanceService.approve(advanceId, "FINANCE001");

        assertEquals("APPROVED", response.status());
    }

    @Test
    void approve_shouldFailWhenManagerApprovesOwnRequest() {
        CashAdvance advance = CashAdvance.builder()
                .advanceId(advanceId)
                .employeeId("MGR001")
                .managerId("MGR001")
                .status("SUBMITTED")
                .build();

        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));

        assertThrows(BusinessRuleViolationException.class, () -> cashAdvanceService.approve(advanceId, "MGR001"));
    }

    @Test
    void disburse_shouldChangeStatusToDisbursed() {
        CashAdvance advance = CashAdvance.builder()
                .advanceId(advanceId)
                .amount(new BigDecimal("1000.00"))
                .status("APPROVED")
                .build();

        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));
        when(cashAdvanceRepository.save(any(CashAdvance.class))).thenAnswer(inv -> inv.getArgument(0));

        CashAdvanceResponse response = cashAdvanceService.disburse(advanceId, "FIN001");

        assertEquals("IN_PROGRESS", response.status());
        assertEquals(new BigDecimal("1000.00"), response.outstandingBalance());
    }

    @Test
    void reject_shouldSucceedWithReasonWhenAuthorizedManager() {
        CashAdvance advance = CashAdvance.builder()
                .advanceId(advanceId)
                .employeeId("EMP001")
                .managerId("MGR001")
                .status("SUBMITTED")
                .build();

        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));
        when(delegationService.canAct("MGR001", "MGR001")).thenReturn(true);
        when(cashAdvanceRepository.save(any(CashAdvance.class))).thenAnswer(inv -> inv.getArgument(0));

        CashAdvanceResponse response = cashAdvanceService.reject(advanceId, "MGR001", "Budget exceeded");

        assertEquals("REJECTED", response.status());
    }

    @Test
    void repay_shouldReduceOutstandingBalanceAndSetSettledWhenBalanceIsZero() {
        CashAdvance advance = CashAdvance.builder()
                .advanceId(advanceId)
                .employeeId("EMP001")
                .amount(new BigDecimal("1000.00"))
                .outstandingBalance(new BigDecimal("400.00"))
                .status("PARTIALLY_SETTLED")
                .build();

        CashAdvanceRepaymentRequest request = new CashAdvanceRepaymentRequest(
                advanceId,
                new BigDecimal("400.00"),
                "BANK_TRANSFER",
                "TXN12345",
                "Full balance repayment"
        );

        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));
        when(currentUserService.getEmployeeId()).thenReturn("EMP001");
        when(cashAdvanceRepaymentRepository.save(any(CashAdvanceRepayment.class))).thenAnswer(inv -> {
            CashAdvanceRepayment saved = inv.getArgument(0);
            saved.setRepaymentId(UUID.randomUUID());
            return saved;
        });

        CashAdvanceRepaymentResponse response = cashAdvanceService.repay(request);

        assertNotNull(response);
        assertEquals(0, advance.getOutstandingBalance().compareTo(BigDecimal.ZERO));
        assertEquals("CLOSED", advance.getStatus());
        verify(cashAdvanceRepository).save(advance);
    }

    @Test
    void getSettlement_shouldCalculateSettlementBreakdownCorrectly() {
        CashAdvance advance = CashAdvance.builder()
                .advanceId(advanceId)
                .employeeId("EMP001")
                .amount(new BigDecimal("1000.00"))
                .outstandingBalance(new BigDecimal("200.00"))
                .currency(testCurrency)
                .settlementDueDate(LocalDate.now().plusDays(5))
                .status("PARTIALLY_SETTLED")
                .build();

        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));
        when(cashAdvanceAdjustmentRepository.findByCashAdvance_AdvanceId(advanceId)).thenReturn(Collections.emptyList());
        when(cashAdvanceRepaymentRepository.findByCashAdvance_AdvanceId(advanceId)).thenReturn(Collections.emptyList());

        CashAdvanceSettlementResponse settlement = cashAdvanceService.getSettlement(advanceId);

        assertNotNull(settlement);
        assertEquals(advanceId, settlement.advanceId());
        assertEquals("EMP001", settlement.employeeId());
        assertEquals(new BigDecimal("1000.00"), settlement.totalAmount());
        assertEquals(new BigDecimal("1000.00"), settlement.outstandingBalance());
    }

    @Test
    void processOverdueAdvances_shouldMarkOverdueAdvancesPastDueDate() {
        CashAdvance pastDueAdvance = CashAdvance.builder()
                .advanceId(advanceId)
                .employeeId("EMP001")
                .amount(new BigDecimal("500.00"))
                .outstandingBalance(new BigDecimal("500.00"))
                .settlementDueDate(LocalDate.now().minusDays(2))
                .status("DISBURSED")
                .build();

        when(cashAdvanceRepository.findByStatusIn(anyList())).thenReturn(List.of(pastDueAdvance));

        cashAdvanceService.processOverdueAdvances();

        assertEquals("OVERDUE", pastDueAdvance.getStatus());
        verify(cashAdvanceRepository).save(pastDueAdvance);
        verify(approvalEventPublisher).publish(eq("CASH_ADVANCE_OVERDUE"), eq(advanceId), anyString());
    }
}
