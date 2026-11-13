package com.expense_management_service.enums;

/**
 * How an employee asks for a cash advance.
 * <ul>
 *   <li>{@link #COMPLETE_NEW}: the full amount for a new trip. Existing advances stay separately tracked.</li>
 *   <li>{@link #ADVANCE_PLUS_OUTSTANDING}: only the additional amount is entered. The eligible outstanding balance is
 *       fetched by the system and rolled over to this request when Finance approves it.</li>
 * </ul>
 */
public enum CashAdvanceRequestType {
    COMPLETE_NEW,
    ADVANCE_PLUS_OUTSTANDING
}
