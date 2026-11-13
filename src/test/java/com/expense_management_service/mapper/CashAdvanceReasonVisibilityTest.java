package com.expense_management_service.mapper;

import com.expense_management_service.dto.response.CashAdvanceResponse;
import com.expense_management_service.entity.CashAdvance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The employee sees why a request was rejected or deferred, and only for the status that has that reason.
 */
class CashAdvanceReasonVisibilityTest {

    private final CashAdvanceMapper mapper = new CashAdvanceMapper();

    private static CashAdvance advance(String status) {
        CashAdvance a = CashAdvance.builder().advanceId(UUID.randomUUID()).employeeId("E1")
                .amount(new BigDecimal("5000.00")).status(status).build();
        a.setRejectionReason("Receipts for the hotel are missing");
        a.setDeferredReason("Wait until the earlier balance is settled");
        return a;
    }

    @Test
    void rejectedRequest_showsItsRejectionReasonOnly() {
        CashAdvanceResponse r = mapper.toResponse(advance("REJECTED"));
        assertEquals("Receipts for the hotel are missing", r.rejectionReason());
        assertNull(r.deferredReason());
    }

    @Test
    void deferredRequest_showsItsDeferralReasonOnly() {
        CashAdvanceResponse r = mapper.toResponse(advance("DEFERRED"));
        assertEquals("Wait until the earlier balance is settled", r.deferredReason());
        assertNull(r.rejectionReason());
    }

    @Test
    void submittedRequest_showsNoReasons() {
        CashAdvanceResponse r = mapper.toResponse(advance("SUBMITTED"));
        assertNull(r.rejectionReason());
        assertNull(r.deferredReason());
    }
}
