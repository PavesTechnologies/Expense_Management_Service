package com.expense_management_service.integration.rms.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * RMS's standard response envelope — verified against a live {@code GET /rms/api/client/{id}}
 * call: {@code {"success":true,"message":"...","data":{...}}}. The actual payload is always
 * nested under {@code data}, never returned bare.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RmsApiResponse<T>(boolean success, String message, T data) {
}
