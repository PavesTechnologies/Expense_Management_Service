package com.expense_management_service.dto.request;

/** Reason for withdrawing a refund notice. Required. */
public record RecoveryCaseActionRequest(String reason) {
}
