package com.expense_management_service.service.impl;

import com.expense_management_service.dto.response.EmployeeAdvanceBalanceResponse.ManagerDecision;
import com.expense_management_service.entity.CashAdvance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What Finance sees about the manager's recommendation before approving: the set-off, the exposure exception, and whether
 * Finance has authorised that exception.
 */
class ManagerDecisionSummaryTest {

    @Test
    void noDecisionYet_givesNoSummary() {
        CashAdvance advance = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId("E1").build();
        assertNull(CashAdvanceServiceImpl.managerDecisionOf(advance));
    }

    @Test
    void setOffRecommendation_carriesSourceAndAmount() {
        UUID source = UUID.randomUUID();
        CashAdvance advance = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId("E1").build();
        advance.setRecommendedTreatment("SET_OFF");
        advance.setRecommendedSourceAdvanceId(source.toString());
        advance.setRecommendedSetOffAmount(new BigDecimal("3000.00"));
        advance.setManagerJustification("Trip 1 expenses are confirmed");
        advance.setManagerDecidedBy("M1");
        advance.setManagerDecidedAt(LocalDateTime.of(2026, 11, 10, 9, 0));

        ManagerDecision d = CashAdvanceServiceImpl.managerDecisionOf(advance);

        assertEquals("SET_OFF", d.treatment());
        assertEquals(source, d.setOffSourceAdvanceId());
        assertEquals(0, new BigDecimal("3000.00").compareTo(d.setOffAmount()));
        assertEquals("Trip 1 expenses are confirmed", d.justification());
        assertEquals("M1", d.decidedBy());
        assertFalse(d.exposureExceptionAuthorized());
    }

    @Test
    void exposureException_showsWhetherFinanceAuthorisedIt() {
        CashAdvance advance = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId("E1").build();
        advance.setRecommendedTreatment("FULL_ADVANCE");
        advance.setExposureExceptionJustification("Urgent client trip");
        advance.setExposureExceptionBy("M1");
        advance.setExposureExceptionAt(LocalDateTime.of(2026, 11, 10, 9, 5));

        ManagerDecision before = CashAdvanceServiceImpl.managerDecisionOf(advance);
        assertEquals("Urgent client trip", before.exposureExceptionJustification());
        assertFalse(before.exposureExceptionAuthorized());
        assertNull(before.setOffSourceAdvanceId());

        advance.setExposureExceptionAuthorizedBy("F1");
        assertTrue(CashAdvanceServiceImpl.managerDecisionOf(advance).exposureExceptionAuthorized());
    }
}
