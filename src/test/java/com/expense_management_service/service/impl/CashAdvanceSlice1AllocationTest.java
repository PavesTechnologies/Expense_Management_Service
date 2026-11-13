package com.expense_management_service.service.impl;

import com.expense_management_service.common.exception.BusinessRuleViolationException;
import com.expense_management_service.dto.request.CashAdvancePreviewRequest;
import com.expense_management_service.dto.request.CashAdvanceRequest;
import com.expense_management_service.dto.response.CashAdvancePreviewResponse;
import com.expense_management_service.entity.CashAdvance;
import com.expense_management_service.enums.CashAdvanceRequestType;
import com.expense_management_service.repository.CashAdvanceRepository;
import com.expense_management_service.repository.EmployeeCacheRepository;
import com.expense_management_service.repository.SystemConfigurationRepository;
import com.expense_management_service.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Slice 1: the two request types, the calculation preview, and the conditional justification. The balances come from the
 * ledger (here, the balance calculator is stubbed to return each advance's stored outstanding balance).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CashAdvanceSlice1AllocationTest {

    private static final String ME = "EMP001";

    @Mock private CashAdvanceRepository cashAdvanceRepository;
    @Mock private CashAdvanceBalanceCalculator balanceCalculator;
    @Mock private SystemConfigurationRepository systemConfigurationRepository;
    @Mock private EmployeeCacheRepository employeeCacheRepository;
    @Mock private CurrentUserService currentUserService;
    @InjectMocks private CashAdvanceServiceImpl service;

    @BeforeEach
    void setUp() {
        when(currentUserService.getEmployeeId()).thenReturn(ME);
        when(systemConfigurationRepository.findByConfigKey(anyString())).thenReturn(Optional.empty());
        when(balanceCalculator.outstanding(any(CashAdvance.class)))
                .thenAnswer(inv -> ((CashAdvance) inv.getArgument(0)).getOutstandingBalance());
    }

    private static CashAdvance advance(String status, String outstanding) {
        CashAdvance a = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId(ME)
                .amount(new BigDecimal(outstanding)).status(status).build();
        a.setOutstandingBalance(new BigDecimal(outstanding));
        return a;
    }

    /** Open balances for the outstanding query; nothing committed (approved but not disbursed) for the committed query. */
    private void openAdvances(CashAdvance... advances) {
        when(cashAdvanceRepository.findByEmployeeIdAndStatusIn(eq(ME), anyList())).thenAnswer(inv -> {
            java.util.Collection<?> statuses = inv.getArgument(1);
            return statuses.contains("DISBURSED") ? List.of(advances) : List.of();
        });
    }

    private static CashAdvanceRequest request(CashAdvanceRequestType type, String amount, BigDecimal additional, String justification) {
        return new CashAdvanceRequest(new BigDecimal(amount), UUID.randomUUID(), null, null, "Client visit", "Pune trip",
                null, LocalDate.now().plusDays(40), LocalDate.now().plusDays(30), null, null,
                type, additional, justification);
    }

    // ------------------------------------------------------------------ preview

    @Test
    void preview_completeNew_withNoOutstanding_needsNoJustification() {
        openAdvances();
        CashAdvancePreviewResponse r = service.preview(new CashAdvancePreviewRequest(
                CashAdvanceRequestType.COMPLETE_NEW, new BigDecimal("8000"), null));

        assertEquals(0, BigDecimal.ZERO.compareTo(r.existingOutstanding()));
        assertFalse(r.justificationRequired());
        assertEquals(0, new BigDecimal("8000").compareTo(r.totalAdvanceAllocation()));
        assertEquals(0, BigDecimal.ZERO.compareTo(r.eligibleOutstanding()), "complete new never includes the outstanding balance");
    }

    @Test
    void preview_completeNew_withOutstanding_requiresJustification_andTotalIsOnlyTheAmount() {
        // Spec example: 4,000 outstanding, 8,000 needed. Total exposure after disbursement: 12,000.
        openAdvances(advance("DISBURSED", "4000"));
        CashAdvancePreviewResponse r = service.preview(new CashAdvancePreviewRequest(
                CashAdvanceRequestType.COMPLETE_NEW, new BigDecimal("8000"), null));

        assertTrue(r.justificationRequired());
        assertEquals(0, new BigDecimal("8000").compareTo(r.totalAdvanceAllocation()));
        assertEquals(0, new BigDecimal("4000").compareTo(r.existingOutstanding()));
        assertEquals(0, new BigDecimal("12000").compareTo(r.potentialExposure()));
    }

    @Test
    void preview_advancePlusOutstanding_fetchesEligibleBalance_andTotalIsEligiblePlusAmount() {
        // Spec example: 4,000 eligible outstanding + 4,000 additional = 8,000 total; 4,000 cash after rollover.
        openAdvances(advance("DISBURSED", "4000"));
        CashAdvancePreviewResponse r = service.preview(new CashAdvancePreviewRequest(
                CashAdvanceRequestType.ADVANCE_PLUS_OUTSTANDING, new BigDecimal("4000"), null));

        assertEquals(CashAdvanceRequestType.ADVANCE_PLUS_OUTSTANDING, r.requestType());
        assertEquals(0, new BigDecimal("4000").compareTo(r.eligibleOutstanding()));
        assertEquals(0, new BigDecimal("4000").compareTo(r.cashRequested()));
        assertEquals(0, new BigDecimal("8000").compareTo(r.totalAdvanceAllocation()));
        assertFalse(r.justificationRequired(), "a justification is not separately required for advance plus outstanding");
    }

    @Test
    void preview_advancePlusOutstanding_leavesOutSubmittedButUnapprovedExpenses() {
        // Expenses awaiting approval are not final, so their advance is not eligible; only the disbursed one counts.
        openAdvances(advance("DISBURSED", "4000"), advance("EXPENSE_SUBMITTED", "1500"));
        CashAdvancePreviewResponse r = service.preview(new CashAdvancePreviewRequest(
                CashAdvanceRequestType.ADVANCE_PLUS_OUTSTANDING, new BigDecimal("2000"), null));

        assertEquals(0, new BigDecimal("4000").compareTo(r.eligibleOutstanding()));
        assertEquals(0, new BigDecimal("5500").compareTo(r.existingOutstanding()), "the employee still owes it, it just is not rolled over");
        assertEquals(0, new BigDecimal("6000").compareTo(r.totalAdvanceAllocation()));
    }

    @Test
    void preview_advancePlusOutstanding_isRefusedWhenNothingIsEligible() {
        openAdvances(advance("EXPENSE_SUBMITTED", "1500"));
        assertThrows(BusinessRuleViolationException.class, () -> service.preview(new CashAdvancePreviewRequest(
                CashAdvanceRequestType.ADVANCE_PLUS_OUTSTANDING, new BigDecimal("2000"), null)));
    }

    @Test
    void preview_treatsAMissingTypeAsCompleteNew() {
        openAdvances();
        CashAdvancePreviewResponse r = service.preview(new CashAdvancePreviewRequest(null, new BigDecimal("1000"), null));
        assertEquals(CashAdvanceRequestType.COMPLETE_NEW, r.requestType());
    }

    @Test
    void preview_rejectsAmountsThatAreNotInRoundedBlocks() {
        openAdvances();
        assertThrows(BusinessRuleViolationException.class, () -> service.preview(new CashAdvancePreviewRequest(
                CashAdvanceRequestType.COMPLETE_NEW, new BigDecimal("1200"), null)));
    }

    // ------------------------------------------------------ create and update rules

    @Test
    void completeNew_withOutstanding_storesTheSnapshotAndJustification() {
        openAdvances(advance("DISBURSED", "4000"));
        CashAdvance entity = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId(ME).build();

        service.applyAllocationRules(entity, request(CashAdvanceRequestType.COMPLETE_NEW, "8000", null,
                "Client needs a separate trip while the earlier one is being settled"));

        assertEquals("COMPLETE_NEW", entity.getRequestType());
        assertNull(entity.getAdditionalAmountRequested());
        assertEquals(0, new BigDecimal("4000").compareTo(entity.getOutstandingAmountSnapshot()));
        assertEquals(0, new BigDecimal("8000").compareTo(entity.getTotalAdvanceAllocation()));
        assertEquals("Client needs a separate trip while the earlier one is being settled", entity.getEmployeeJustification());
    }

    @Test
    void completeNew_withOutstanding_isRefusedWithoutAJustification() {
        openAdvances(advance("DISBURSED", "4000"));
        CashAdvance entity = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId(ME).build();

        assertThrows(BusinessRuleViolationException.class, () -> service.applyAllocationRules(entity,
                request(CashAdvanceRequestType.COMPLETE_NEW, "8000", null, null)));
    }

    @Test
    void completeNew_withNoOutstanding_doesNotAskForAJustification() {
        openAdvances();
        CashAdvance entity = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId(ME).build();

        service.applyAllocationRules(entity, request(CashAdvanceRequestType.COMPLETE_NEW, "8000", null, null));

        assertNull(entity.getEmployeeJustification());
        assertEquals(0, new BigDecimal("8000").compareTo(entity.getTotalAdvanceAllocation()));
    }

    @Test
    void advancePlusOutstanding_storesTheAllocation_andTheAdditionalAmount() {
        openAdvances(advance("DISBURSED", "4000"));
        CashAdvance entity = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId(ME).build();

        service.applyAllocationRules(entity, request(CashAdvanceRequestType.ADVANCE_PLUS_OUTSTANDING, "4000", new BigDecimal("4000"), null));

        assertEquals("ADVANCE_PLUS_OUTSTANDING", entity.getRequestType());
        assertEquals(0, new BigDecimal("4000").compareTo(entity.getAdditionalAmountRequested()));
        assertEquals(0, new BigDecimal("8000").compareTo(entity.getTotalAdvanceAllocation()));
        assertNull(entity.getEmployeeJustification(), "no separate justification for advance plus outstanding");
    }

    @Test
    void advancePlusOutstanding_refusesAnAdditionalAmountThatDiffersFromTheAmount() {
        openAdvances(advance("DISBURSED", "4000"));
        CashAdvance entity = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId(ME).build();

        assertThrows(BusinessRuleViolationException.class, () -> service.applyAllocationRules(entity,
                request(CashAdvanceRequestType.ADVANCE_PLUS_OUTSTANDING, "4000", new BigDecimal("5000"), null)));
    }

    @Test
    void completeNew_refusesAnAdditionalAmount() {
        openAdvances();
        CashAdvance entity = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId(ME).build();

        assertThrows(BusinessRuleViolationException.class, () -> service.applyAllocationRules(entity,
                request(CashAdvanceRequestType.COMPLETE_NEW, "8000", new BigDecimal("8000"), null)));
    }

    @Test
    void nullRequestType_isStoredAsCompleteNew() {
        openAdvances();
        CashAdvance entity = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId(ME).build();

        service.applyAllocationRules(entity, request(null, "2000", null, null));

        assertEquals("COMPLETE_NEW", entity.getRequestType());
    }

    // ------------------------------------------------------------------- profile

    @Test
    void profile_isReadOnlyIdentity_fromTheEmployeeCache_andNeverShowsAnIdAsAName() {
        com.expense_management_service.entity.EmployeeCache me = com.expense_management_service.entity.EmployeeCache.builder()
                .employeeId(ME).firstName("Ravi").lastName("Menon").managerEmployeeId("MGR1").build();
        com.expense_management_service.entity.EmployeeCache manager = com.expense_management_service.entity.EmployeeCache.builder()
                .employeeId("MGR1").firstName("Anita").lastName("Rao").build();
        when(employeeCacheRepository.findByEmployeeId(ME)).thenReturn(Optional.of(me));
        when(employeeCacheRepository.findByEmployeeId("MGR1")).thenReturn(Optional.of(manager));

        var profile = service.myProfile();

        assertEquals(ME, profile.employeeId());
        assertEquals("Ravi Menon", profile.fullName());
        assertEquals("Anita Rao", profile.managerName());
        assertNull(profile.departmentName(), "department name is not resolvable yet, so it is not shown");
        assertNull(profile.designationName());
    }

    @Test
    void profile_forAnEmployeeNotInTheCache_showsOnlyTheId() {
        when(employeeCacheRepository.findByEmployeeId(ME)).thenReturn(Optional.empty());
        var profile = service.myProfile();
        assertEquals(ME, profile.employeeId());
        assertNull(profile.fullName());
        assertNull(profile.managerName());
    }

    // ------------------------------------------- review fixes: justification cannot be bypassed, and safety stops

    @Test
    void completeNew_withOnlyAnIneligibleBalance_stillRequiresJustification() {
        // An expense-submitted advance is not eligible for rollover, but the employee still owes it, so the reason is required.
        openAdvances(advance("EXPENSE_SUBMITTED", "1500"));
        CashAdvancePreviewResponse r = service.preview(new CashAdvancePreviewRequest(
                CashAdvanceRequestType.COMPLETE_NEW, new BigDecimal("8000"), null));
        assertTrue(r.justificationRequired());

        CashAdvance entity = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId(ME).build();
        assertThrows(BusinessRuleViolationException.class, () -> service.applyAllocationRules(entity,
                request(CashAdvanceRequestType.COMPLETE_NEW, "8000", null, null)));
    }

    @Test
    void approve_refusesAdvancePlusOutstanding_untilItsRolloverExists() {
        CashAdvance entity = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId(ME)
                .amount(new BigDecimal("4000")).status("PENDING_FINANCE_APPROVAL").build();
        entity.setRequestType("ADVANCE_PLUS_OUTSTANDING");
        when(cashAdvanceRepository.findById(entity.getAdvanceId())).thenReturn(Optional.of(entity));

        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class,
                () -> service.approve(entity.getAdvanceId(), "FINANCE_APPROVER"));
        assertTrue(ex.getMessage().contains("rollover"), ex.getMessage());
        assertEquals("PENDING_FINANCE_APPROVAL", entity.getStatus(), "the advance must not be approved");
    }

    @Test
    void disburse_refusesAdvancePlusOutstanding_evenIfItWereApproved() {
        CashAdvance entity = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId(ME)
                .amount(new BigDecimal("4000")).status("APPROVED").build();
        entity.setRequestType("ADVANCE_PLUS_OUTSTANDING");
        when(cashAdvanceRepository.findById(entity.getAdvanceId())).thenReturn(Optional.of(entity));

        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class,
                () -> service.disburse(entity.getAdvanceId(), "FINANCE_USER", null));
        assertTrue(ex.getMessage().contains("rollover"), ex.getMessage());
        assertNull(entity.getDisbursedAt(), "no cash may be recorded as paid");
    }

    @Test
    void update_refusesASubmittedRequest_becauseItIsReadOnly() {
        CashAdvance entity = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId(ME)
                .amount(new BigDecimal("8000")).status("SUBMITTED").build();
        when(cashAdvanceRepository.findById(entity.getAdvanceId())).thenReturn(Optional.of(entity));

        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class,
                () -> service.update(entity.getAdvanceId(), request(CashAdvanceRequestType.COMPLETE_NEW, "9000", null, null)));
        assertTrue(ex.getMessage().contains("read-only"), ex.getMessage());
        verify(cashAdvanceRepository, never()).save(any());
    }
}
