package com.expense_management_service.event;

import java.util.UUID;

/** A client-billable expense was handed off; its record ({@code syncId}) is to be delivered to AR once committed. */
public record InvoiceHandoffRecordedEvent(UUID syncId) {
}
