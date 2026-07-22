package com.expense_management_service.common;

import java.time.Instant;

/**
 * Uniform response envelope for all XMS API responses.
 *
 * @param success   whether the request succeeded
 * @param message   human-readable summary
 * @param data      payload, {@code null} on error
 * @param timestamp server time the response was created
 * @param <T>       payload type
 */
public record ApiResponse<T>(boolean success, String message, T data, Instant timestamp) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "Success", data, Instant.now());
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data, Instant.now());
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, message, null, Instant.now());
    }
}
