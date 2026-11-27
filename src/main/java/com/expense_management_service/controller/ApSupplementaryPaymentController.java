package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.SupplementaryPaymentConfirmRequest;
import com.expense_management_service.dto.response.SupplementaryPaymentResponse;
import com.expense_management_service.service.SupplementaryPaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** AP payment for supplementary claims. Same AP role gate as the existing AP payment endpoints. */
@RestController
@RequestMapping("/xms/ap/supplementary-payments")
@RequiredArgsConstructor
@PreAuthorize("hasRole('AP_EXECUTIVE')")
public class ApSupplementaryPaymentController {

    private final SupplementaryPaymentService service;

    @GetMapping("/queue")
    public ApiResponse<List<SupplementaryPaymentResponse>> queue() {
        return ApiResponse.success(service.queue());
    }

    /** Supplementary payments AP has already made, so the AP Payments page can list them with the paid reports. */
    @GetMapping("/paid")
    public ApiResponse<List<SupplementaryPaymentResponse>> paid() {
        return ApiResponse.success(service.paid());
    }

    @PostMapping("/{paymentId}/confirm")
    public ApiResponse<SupplementaryPaymentResponse> confirm(@PathVariable UUID paymentId,
                                                             @Valid @RequestBody SupplementaryPaymentConfirmRequest request) {
        return ApiResponse.success("Payment recorded. The claim is settled.", service.confirm(paymentId, request));
    }
}
