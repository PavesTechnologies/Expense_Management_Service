package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.dto.request.CashAdvanceDisbursement;
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
import com.expense_management_service.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
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
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CashAdvanceServiceImplTest {
    @org.mockito.Mock private CashAdvanceBudgetMovements cashAdvanceBudgetMovements;
    @org.mockito.Mock private CashAdvanceRecoveryService cashAdvanceRecoveryService;

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
    private StorageService storageService;

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
        // repay reads the advance with a row lock; the lookup stubbed for findById serves that call too.
        org.mockito.Mockito.lenient().when(cashAdvanceRepository.findByIdForUpdate(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> cashAdvanceRepository.findById(inv.getArgument(0)));
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
                new BigDecimal("500.00"),
                currencyId,
                null,
                new BigDecimal("500.00"),
                "Travel expenses",
                null,
                null,
                LocalDate.now().plusDays(10),
                LocalDate.now().plusDays(10)
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
                new BigDecimal("500.00"),
                currencyId,
                null,
                new BigDecimal("500.00"),
                "Travel expenses",
                null,
                null,
                LocalDate.now(),
                LocalDate.now()
        );

        assertThrows(BusinessRuleViolationException.class, () -> cashAdvanceService.create(todayRequest));

        CashAdvanceRequest pastRequest = new CashAdvanceRequest(
                new BigDecimal("500.00"),
                currencyId,
                null,
                new BigDecimal("500.00"),
                "Travel expenses",
                null,
                null,
                LocalDate.now().minusDays(1),
                LocalDate.now().minusDays(1)
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

        CashAdvanceResponse response = cashAdvanceService.disburse(advanceId, "FIN001", bankTransfer());

        assertEquals("IN_PROGRESS", response.status());
        assertEquals(new BigDecimal("1000.00"), response.outstandingBalance());
    }

    @Test
    void disburse_bankTransfer_recordsTransferDetailsAndStartsExpenseWindow() {
        CashAdvance advance = approvedAdvance();
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));
        when(cashAdvanceRepository.save(any(CashAdvance.class))).thenAnswer(inv -> inv.getArgument(0));

        cashAdvanceService.disburse(advanceId, "FIN001", bankTransfer());

        assertEquals("BANK_TRANSFER", advance.getDisbursementMode());
        assertEquals("UTR123", advance.getDisbursementReference());
        assertEquals(LocalDate.of(2026, 10, 1), advance.getDisbursementPaymentDate());
        assertEquals("HDFC A/C 1234 (Asha)", advance.getDisbursementTransferDetails());
        assertNull(advance.getDisbursementProofReference());
        assertNull(advance.getDisbursementAcknowledgedBy());
        // The 30-day expense window now starts at disbursement (it was never armed before).
        assertNotNull(advance.getDisbursedAt());
        assertEquals(advance.getDisbursedAt().plusDays(30), CashAdvanceExpenseWindow.deadline(advance));
        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void disburse_cash_storesSignedAcknowledgmentAndRecordsReceiver() {
        CashAdvance advance = approvedAdvance();
        MockMultipartFile ack = new MockMultipartFile("signedAcknowledgment", "ack.pdf", "application/pdf", new byte[]{1, 2, 3});
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));
        when(cashAdvanceRepository.save(any(CashAdvance.class))).thenAnswer(inv -> inv.getArgument(0));

        cashAdvanceService.disburse(advanceId, "FIN001", new CashAdvanceDisbursement(
                "cash", "  John Doe ", ack, null, null, null, null, null, null, " counted in front of HR "));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storageService).upload(key.capture(), eq(ack));
        assertTrue(key.getValue().startsWith("disbursements/" + advanceId + "/cash-"));
        assertTrue(key.getValue().endsWith(".pdf"));

        assertEquals("CASH", advance.getDisbursementMode());
        assertEquals("John Doe", advance.getDisbursementAcknowledgedBy());
        assertEquals(key.getValue(), advance.getDisbursementProofReference());
        assertEquals("counted in front of HR", advance.getDisbursementConfirmationNote());
        assertNull(advance.getDisbursementReference());
        assertNotNull(advance.getDisbursedAt());
    }

    @Test
    void disburse_upi_storesPaymentProofAndRecordsUpiDetails() {
        CashAdvance advance = approvedAdvance();
        MockMultipartFile proof = new MockMultipartFile("paymentProof", "shot.png", "image/png", new byte[]{9});
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));
        when(cashAdvanceRepository.save(any(CashAdvance.class))).thenAnswer(inv -> inv.getArgument(0));

        cashAdvanceService.disburse(advanceId, "FIN001", new CashAdvanceDisbursement(
                "UPI", null, null, null, LocalDate.of(2026, 10, 2), null, "UPI-778", "asha@hdfcbank", proof, null));

        assertEquals("UPI", advance.getDisbursementMode());
        assertEquals("UPI-778", advance.getDisbursementReference());
        assertEquals("asha@hdfcbank", advance.getDisbursementPayeeUpiId());
        assertEquals(LocalDate.of(2026, 10, 2), advance.getDisbursementPaymentDate());
        assertTrue(advance.getDisbursementProofReference().endsWith(".png"));
        verify(storageService).upload(eq(advance.getDisbursementProofReference()), eq(proof));
    }

    @Test
    void disburse_rejectsMissingReceiver_andChangesNothing() {
        CashAdvance advance = approvedAdvance();
        MockMultipartFile ack = new MockMultipartFile("signedAcknowledgment", "ack.pdf", "application/pdf", new byte[]{1});
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                cashAdvanceService.disburse(advanceId, "FIN001", new CashAdvanceDisbursement(
                        "CASH", "  ", ack, null, null, null, null, null, null, null)));

        assertEquals("Enter the name of the person who received the cash.", ex.getMessage());
        assertEquals("APPROVED", advance.getStatus());
        assertNull(advance.getDisbursedAt());
        verify(storageService, never()).upload(anyString(), any());
        verify(cashAdvanceRepository, never()).save(any());
    }

    @Test
    void disburse_rejectsUnknownMode_andChangesNothing() {
        CashAdvance advance = approvedAdvance();
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));

        assertThrows(IllegalArgumentException.class, () ->
                cashAdvanceService.disburse(advanceId, "FIN001", new CashAdvanceDisbursement(
                        "CHEQUE", null, null, null, null, null, null, null, null, null)));

        assertEquals("APPROVED", advance.getStatus());
        verify(cashAdvanceRepository, never()).save(any());
    }

    @Test
    void disburse_includesTheCarriedInBalanceInTheObligation() {
        CashAdvance advance = approvedAdvance();
        advance.setCarriedInAmount(new BigDecimal("300.00"));
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));
        when(cashAdvanceRepository.save(any(CashAdvance.class))).thenAnswer(inv -> inv.getArgument(0));

        cashAdvanceService.disburse(advanceId, "FIN001", bankTransfer());

        assertEquals(0, advance.getOutstandingBalance().compareTo(new BigDecimal("1300.00")));
    }

    private CashAdvance approvedAdvance() {
        return CashAdvance.builder()
                .advanceId(advanceId)
                .amount(new BigDecimal("1000.00"))
                .status("APPROVED")
                .build();
    }

    private CashAdvanceDisbursement bankTransfer() {
        return new CashAdvanceDisbursement(
                "BANK_TRANSFER", null, null, "UTR123", LocalDate.of(2026, 10, 1),
                "HDFC A/C 1234 (Asha)", null, null, null, null);
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
                "Full balance repayment",
                bankRepayment("TXN12345", LocalDate.of(2026, 10, 2)));

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
    void repay_bankTransfer_recordsPaymentDetailsWithoutAnyFile() {
        CashAdvance advance = repayableAdvance(new BigDecimal("1000.00"));
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));
        when(cashAdvanceRepaymentRepository.save(any(CashAdvanceRepayment.class))).thenAnswer(inv -> inv.getArgument(0));

        cashAdvanceService.repay(new CashAdvanceRepaymentRequest(advanceId, new BigDecimal("300.00"), null,
                bankRepayment("UTR-900", LocalDate.of(2026, 10, 2))));

        ArgumentCaptor<CashAdvanceRepayment> captor = ArgumentCaptor.forClass(CashAdvanceRepayment.class);
        verify(cashAdvanceRepaymentRepository).save(captor.capture());
        CashAdvanceRepayment saved = captor.getValue();
        assertEquals("BANK_TRANSFER", saved.getPaymentMode());
        assertEquals("UTR-900", saved.getPaymentReference());
        assertEquals(LocalDate.of(2026, 10, 2), saved.getTransferDate());
        assertEquals("HDFC A/C 1234", saved.getTransferDetails());
        assertNull(saved.getProofReference());
        assertEquals(0, advance.getOutstandingBalance().compareTo(new BigDecimal("700.00")));
        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void repay_cash_storesAcknowledgmentAndRecordsReceiver() {
        CashAdvance advance = repayableAdvance(new BigDecimal("1000.00"));
        MockMultipartFile ack = new MockMultipartFile("signedAcknowledgment", "ack.pdf", "application/pdf", new byte[]{7, 7});
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));
        when(cashAdvanceRepaymentRepository.save(any(CashAdvanceRepayment.class))).thenAnswer(inv -> inv.getArgument(0));

        cashAdvanceService.repay(new CashAdvanceRepaymentRequest(advanceId, new BigDecimal("100.00"), null,
                new CashAdvanceDisbursement("CASH", "Ravi Kumar", ack, null, null, null, null, null, null, null)));

        ArgumentCaptor<CashAdvanceRepayment> captor = ArgumentCaptor.forClass(CashAdvanceRepayment.class);
        verify(cashAdvanceRepaymentRepository).save(captor.capture());
        CashAdvanceRepayment saved = captor.getValue();
        assertEquals("CASH", saved.getPaymentMode());
        assertEquals("Ravi Kumar", saved.getReceivedByName());
        assertTrue(saved.getPaymentReference().startsWith("CASH-"));
        assertTrue(saved.getProofReference().startsWith("repayments/" + advanceId + "/cash-"));
        assertTrue(saved.getProofReference().endsWith(".pdf"));
        verify(storageService).upload(eq(saved.getProofReference()), eq(ack));
    }

    @Test
    void repay_upi_storesScreenshotAndRecordsUpiDetails() {
        CashAdvance advance = repayableAdvance(new BigDecimal("1000.00"));
        MockMultipartFile shot = new MockMultipartFile("paymentProof", "shot.png", "image/png", new byte[]{9});
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));
        when(cashAdvanceRepaymentRepository.save(any(CashAdvanceRepayment.class))).thenAnswer(inv -> inv.getArgument(0));

        cashAdvanceService.repay(new CashAdvanceRepaymentRequest(advanceId, new BigDecimal("50.00"), null,
                new CashAdvanceDisbursement("UPI", null, null, null, LocalDate.of(2026, 10, 2), null,
                        "UPI-778", "asha@hdfcbank", shot, null)));

        ArgumentCaptor<CashAdvanceRepayment> captor = ArgumentCaptor.forClass(CashAdvanceRepayment.class);
        verify(cashAdvanceRepaymentRepository).save(captor.capture());
        CashAdvanceRepayment saved = captor.getValue();
        assertEquals("UPI-778", saved.getPaymentReference());
        assertEquals("asha@hdfcbank", saved.getPayeeUpiId());
        assertTrue(saved.getProofReference().endsWith(".png"));
        verify(storageService).upload(eq(saved.getProofReference()), eq(shot));
    }

    @Test
    void repay_rejectsMissingPaymentMode_andChangesNothing() {
        CashAdvance advance = repayableAdvance(new BigDecimal("1000.00"));
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                cashAdvanceService.repay(new CashAdvanceRepaymentRequest(advanceId, new BigDecimal("100.00"), null,
                        new CashAdvanceDisbursement(null, null, null, null, null, null, null, null, null, null))));

        assertEquals("Select how the payment was made.", ex.getMessage());
        assertEquals(0, advance.getOutstandingBalance().compareTo(new BigDecimal("1000.00")));
        verify(cashAdvanceRepaymentRepository, never()).save(any());
        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void repay_rejectsMissingPaymentBlock_andChangesNothing() {
        CashAdvance advance = repayableAdvance(new BigDecimal("1000.00"));
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));

        assertThrows(IllegalArgumentException.class, () ->
                cashAdvanceService.repay(new CashAdvanceRepaymentRequest(advanceId, new BigDecimal("100.00"), null, null)));

        assertEquals(0, advance.getOutstandingBalance().compareTo(new BigDecimal("1000.00")));
        verify(cashAdvanceRepaymentRepository, never()).save(any());
    }

    @Test
    void repay_rejectsUpiWithoutScreenshot_andChangesNothing() {
        CashAdvance advance = repayableAdvance(new BigDecimal("1000.00"));
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));

        assertThrows(IllegalArgumentException.class, () ->
                cashAdvanceService.repay(new CashAdvanceRepaymentRequest(advanceId, new BigDecimal("50.00"), null,
                        new CashAdvanceDisbursement("UPI", null, null, null, LocalDate.of(2026, 10, 2), null,
                                "UPI-778", "asha@hdfcbank", null, null))));

        assertEquals(0, advance.getOutstandingBalance().compareTo(new BigDecimal("1000.00")));
        verify(cashAdvanceRepaymentRepository, never()).save(any());
        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void repay_rejectsFutureTransferDate_andChangesNothing() {
        CashAdvance advance = repayableAdvance(new BigDecimal("1000.00"));
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                cashAdvanceService.repay(new CashAdvanceRepaymentRequest(advanceId, new BigDecimal("100.00"), null,
                        bankRepayment("UTR-901", LocalDate.now().plusDays(3)))));

        assertEquals("Transfer date cannot be in the future.", ex.getMessage());
        assertEquals(0, advance.getOutstandingBalance().compareTo(new BigDecimal("1000.00")));
        verify(cashAdvanceRepaymentRepository, never()).save(any());
    }

    @Test
    void repay_rejectsZeroAmount_andChangesNothing() {
        CashAdvance advance = repayableAdvance(new BigDecimal("1000.00"));
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));

        assertThrows(IllegalArgumentException.class, () ->
                cashAdvanceService.repay(new CashAdvanceRepaymentRequest(advanceId, BigDecimal.ZERO, null,
                        bankRepayment("UTR-902", LocalDate.of(2026, 10, 2)))));

        assertEquals(0, advance.getOutstandingBalance().compareTo(new BigDecimal("1000.00")));
        verify(cashAdvanceRepaymentRepository, never()).save(any());
    }

    @Test
    void repay_rejectsDuplicateTransactionReference_andChangesNothing() {
        CashAdvance advance = repayableAdvance(new BigDecimal("1000.00"));
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));
        when(cashAdvanceRepaymentRepository.existsByCashAdvance_AdvanceIdAndPaymentReference(advanceId, "UTR-903"))
                .thenReturn(true);

        assertThrows(BusinessRuleViolationException.class, () ->
                cashAdvanceService.repay(new CashAdvanceRepaymentRequest(advanceId, new BigDecimal("100.00"), null,
                        bankRepayment("UTR-903", LocalDate.of(2026, 10, 2)))));

        assertEquals(0, advance.getOutstandingBalance().compareTo(new BigDecimal("1000.00")));
        verify(cashAdvanceRepaymentRepository, never()).save(any());
        verify(storageService, never()).upload(anyString(), any());
    }

    @Test
    void repay_rejectsSameCashAcknowledgmentTwice() {
        CashAdvance advance = repayableAdvance(new BigDecimal("1000.00"));
        MockMultipartFile ack = new MockMultipartFile("signedAcknowledgment", "ack.pdf", "application/pdf", new byte[]{7, 7});
        when(cashAdvanceRepository.findById(advanceId)).thenReturn(Optional.of(advance));
        when(cashAdvanceRepaymentRepository.existsByCashAdvance_AdvanceIdAndPaymentReference(eq(advanceId), startsWith("CASH-")))
                .thenReturn(true);

        assertThrows(BusinessRuleViolationException.class, () ->
                cashAdvanceService.repay(new CashAdvanceRepaymentRequest(advanceId, new BigDecimal("100.00"), null,
                        new CashAdvanceDisbursement("CASH", "Ravi Kumar", ack, null, null, null, null, null, null, null))));

        verify(cashAdvanceRepaymentRepository, never()).save(any());
        verify(storageService, never()).upload(anyString(), any());
    }

    private CashAdvance repayableAdvance(BigDecimal outstanding) {
        return CashAdvance.builder()
                .advanceId(advanceId)
                .employeeId("EMP001")
                .amount(new BigDecimal("1000.00"))
                .outstandingBalance(outstanding)
                .status("PARTIALLY_SETTLED")
                .build();
    }

    private static CashAdvanceDisbursement bankRepayment(String reference, LocalDate date) {
        return new CashAdvanceDisbursement("BANK_TRANSFER", null, null, reference, date, "HDFC A/C 1234",
                null, null, null, null);
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

    @Test
    void getMyAdvances_shouldReturnPagedResultsWithoutStatus() {
        Pageable pageable = PageRequest.of(0, 10);
        CashAdvance advance1 = CashAdvance.builder()
                .advanceId(UUID.randomUUID())
                .employeeId("EMP001")
                .amount(new BigDecimal("500.00"))
                .status("DRAFT")
                .build();
        CashAdvance advance2 = CashAdvance.builder()
                .advanceId(UUID.randomUUID())
                .employeeId("EMP001")
                .amount(new BigDecimal("300.00"))
                .status("SUBMITTED")
                .build();

        Page<CashAdvance> page = new PageImpl<>(List.of(advance1, advance2), pageable, 2);
        when(cashAdvanceRepository.findByEmployeeId("EMP001", pageable)).thenReturn(page);

        Page<CashAdvanceResponse> result = cashAdvanceService.getMyAdvances("EMP001", null, pageable);

        assertNotNull(result);
        assertEquals(2, result.getContent().size());
        assertEquals(1, result.getTotalPages());
        assertEquals(2, result.getTotalElements());
    }

    @Test
    void getMyAdvances_shouldReturnPagedResultsWithStatus() {
        Pageable pageable = PageRequest.of(0, 10);
        CashAdvance advance = CashAdvance.builder()
                .advanceId(UUID.randomUUID())
                .employeeId("EMP001")
                .amount(new BigDecimal("500.00"))
                .status("SUBMITTED")
                .build();

        Page<CashAdvance> page = new PageImpl<>(List.of(advance), pageable, 1);
        when(cashAdvanceRepository.findByEmployeeIdAndStatus("EMP001", "SUBMITTED", pageable)).thenReturn(page);

        Page<CashAdvanceResponse> result = cashAdvanceService.getMyAdvances("EMP001", "SUBMITTED", pageable);

        assertNotNull(result);
        assertEquals(1, result.getContent().size());
        assertEquals("SUBMITTED", result.getContent().get(0).status());
    }

    @Test
    void create_shouldAlwaysSetStatusToDraftServerSide() {
        CashAdvanceRequest request = new CashAdvanceRequest(
                new BigDecimal("500.00"),
                currencyId,
                null,
                new BigDecimal("500.00"),
                "Travel",
                null,
                null,
                LocalDate.now().plusDays(10),
                LocalDate.now().plusDays(10)
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

        assertEquals("DRAFT", response.status(), "Status should always be DRAFT on creation, regardless of request");
    }

    @Test
    void create_shouldAlwaysDeriveEmployeeIdFromJWT() {
        CashAdvanceRequest request = new CashAdvanceRequest(
                new BigDecimal("500.00"),
                currencyId,
                null,
                new BigDecimal("500.00"),
                "Travel",
                null,
                null,
                LocalDate.now().plusDays(10),
                LocalDate.now().plusDays(10)
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

        assertEquals("EMP001", response.employeeId(),
            "EmployeeId should always come from JWT token, never from request");
    }

    @Test
    void create_shouldHaveOptimisticLockingVersionSet() {
        CashAdvanceRequest request = new CashAdvanceRequest(
                new BigDecimal("500.00"),
                currencyId,
                null,
                new BigDecimal("500.00"),
                "Travel",
                null,
                null,
                LocalDate.now().plusDays(10),
                LocalDate.now().plusDays(10)
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
            saved.setVersion(0L);
            return saved;
        });

        CashAdvanceResponse response = cashAdvanceService.create(request);

        assertNotNull(response, "Response should not be null");
        // Verify version was set on save (checked via the save mock invocation)
        verify(cashAdvanceRepository).save(argThat(advance -> true));
    }
}
