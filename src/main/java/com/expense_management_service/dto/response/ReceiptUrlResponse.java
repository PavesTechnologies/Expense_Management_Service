package com.expense_management_service.dto.response;

import java.time.LocalDateTime;

/** A time-limited pre-signed S3 URL for viewing or downloading a receipt file. */
public record ReceiptUrlResponse(String url, LocalDateTime expiresAt) {
}
