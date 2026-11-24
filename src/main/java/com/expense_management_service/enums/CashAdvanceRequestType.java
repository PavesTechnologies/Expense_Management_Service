package com.expense_management_service.enums;

/**
 * How an employee asks for a cash advance.
 * <ul>
 *   <li>{@link #COMPLETE_NEW}: the full amount for a new trip. Existing advances stay separately tracked.</li>
 *   <li>{@link #ADVANCE_PLUS_OUTSTANDING}: only the additional amount is entered. The eligible outstanding balance is
 *       fetched by the system and rolled over to this request when Finance approves it. Currently refused.</li>
 *   <li>{@link #ADVANCE_PLUS_CARRY_FORWARD}: only the new cash is entered. The balance Finance has already carried
 *       forward (held, not yet taken in by another advance) moves into this request when it is finally approved. Only
 *       the new cash is paid out.</li>
 * </ul>
 */
public enum CashAdvanceRequestType {
    COMPLETE_NEW,
    ADVANCE_PLUS_OUTSTANDING,
    ADVANCE_PLUS_CARRY_FORWARD
}
