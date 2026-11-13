package com.expense_management_service.dto.response;

import com.expense_management_service.enums.CashAdvanceRequestType;

import java.math.BigDecimal;

/**
 * The calculation the employee sees before submitting. Calculated on the server from the advance ledger.
 */
public record CashAdvancePreviewResponse(
        CashAdvanceRequestType requestType,
        String currencyCode,
        /** Total still owed across all open advances. Shown so the employee knows the full position. */
        BigDecimal existingOutstanding,
        /** The part of the outstanding balance that may be rolled into this request. Zero for COMPLETE_NEW. */
        BigDecimal eligibleOutstanding,
        /** Cash the employee is asking for now: the full amount, or the additional amount. */
        BigDecimal cashRequested,
        /** eligibleOutstanding + cashRequested for ADVANCE_PLUS_OUTSTANDING; cashRequested otherwise. */
        BigDecimal totalAdvanceAllocation,
        /** True when a reason for a separate advance must be given. */
        boolean justificationRequired,
        /** Company limit on total exposure. Null when no limit is configured. */
        BigDecimal exposureLimit,
        /** Open balances plus the cash requested now. */
        BigDecimal potentialExposure,
        boolean exceedsLimit,
        BigDecimal excessOverLimit
) {
}
