package com.expense_management_service.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.LateReceiptReviewRequest;
import com.expense_management_service.dto.response.LateReceiptResponse;
import com.expense_management_service.service.ReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Late and missing receipts: a receipt found after its report was approved, reimbursed or closed.
 * <p>
 * The employee attaches it to the original expense. Finance/AP then verify or reject it. None of these calls changes the
 * expense's amount, status or payment.
 */
@RestController
@RequiredArgsConstructor
public class LateReceiptController {

    private final ReceiptService receiptService;

    @PostMapping(value = "/xms/employee/expense-line-items/{lineItemId}/late-receipts", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL')")
    public ApiResponse<LateReceiptResponse> attach(@PathVariable UUID lineItemId,
                                                   @RequestParam("file") MultipartFile file,
                                                   @RequestParam("actualReceiptDate")
                                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate actualReceiptDate,
                                                   @RequestParam("reason") String reason) {
        LateReceiptResponse saved = receiptService.attachLate(lineItemId, file, actualReceiptDate, reason);
        return ApiResponse.success("Late receipt attached. It is waiting for Finance or AP review.", saved);
    }

    @GetMapping("/xms/employee/expense-reports/{reportId}/late-receipts")
    @PreAuthorize("hasAnyRole('ADMIN','GENERAL','FINANCE','MANAGER','FINANCE_EXECUTIVE','AP_EXECUTIVE')")
    public ApiResponse<List<LateReceiptResponse>> listForReport(@PathVariable UUID reportId) {
        return ApiResponse.success("Late receipts retrieved", receiptService.getLateForReport(reportId));
    }

    @GetMapping("/xms/finance/late-receipts")
    @PreAuthorize("hasAnyRole('FINANCE','FINANCE_EXECUTIVE','AP_EXECUTIVE')")
    public ApiResponse<List<LateReceiptResponse>> pendingReview() {
        return ApiResponse.success("Late receipts awaiting review retrieved", receiptService.getPendingLateReceipts());
    }

    @PostMapping("/xms/finance/late-receipts/{receiptId}/verify")
    @PreAuthorize("hasAnyRole('FINANCE','FINANCE_EXECUTIVE','AP_EXECUTIVE')")
    public ApiResponse<LateReceiptResponse> verify(@PathVariable UUID receiptId,
                                                   @RequestBody(required = false) LateReceiptReviewRequest request) {
        String note = request == null ? null : request.note();
        return ApiResponse.success("Late receipt verified", receiptService.verifyLate(receiptId, note));
    }

    @PostMapping("/xms/finance/late-receipts/{receiptId}/reject")
    @PreAuthorize("hasAnyRole('FINANCE','FINANCE_EXECUTIVE','AP_EXECUTIVE')")
    public ApiResponse<LateReceiptResponse> reject(@PathVariable UUID receiptId,
                                                   @RequestBody(required = false) LateReceiptReviewRequest request) {
        String note = request == null ? null : request.note();
        return ApiResponse.success("Late receipt rejected", receiptService.rejectLate(receiptId, note));
    }
}
