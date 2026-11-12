package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.response.PaymentProofFields;
import com.expense_management_service.service.impl.PaymentProofExtractor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Reads a payment screenshot so Finance can fill the disbursement or repayment form from it. */
@RestController
@RequestMapping("/xms/finance/payment-proofs")
@RequiredArgsConstructor
public class PaymentProofController {

    private final PaymentProofExtractor paymentProofExtractor;

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','FINANCE','FINANCE_EXECUTIVE')")
    public ApiResponse<PaymentProofFields> extract(@RequestParam MultipartFile file) {
        return ApiResponse.success(paymentProofExtractor.extract(file));
    }
}
