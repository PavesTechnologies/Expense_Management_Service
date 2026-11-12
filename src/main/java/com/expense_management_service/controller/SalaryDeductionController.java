package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.request.SalaryDeductionCancelRequest;
import com.expense_management_service.dto.request.SalaryDeductionCreateRequest;
import com.expense_management_service.dto.response.PayrollMonthOption;
import com.expense_management_service.dto.response.SalaryDeductionResponse;
import com.expense_management_service.service.impl.SalaryDeductionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Finance-initiated salary deduction. Finance requests the recovery, Payroll processes it, and Finance records the
 * result. Payroll has no login in this application yet, so recording the result stays with Finance; see the audit notes.
 */
@RestController
@RequestMapping("/xms/finance/salary-deductions")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','FINANCE','FINANCE_EXECUTIVE')")
public class SalaryDeductionController {

    private final SalaryDeductionService salaryDeductionService;

    @GetMapping("/payroll-months")
    public ApiResponse<List<PayrollMonthOption>> payrollMonths() {
        return ApiResponse.success(salaryDeductionService.eligiblePayrollMonths());
    }

    @GetMapping("/cash-advances/{advanceId}")
    public ApiResponse<List<SalaryDeductionResponse>> listForAdvance(@PathVariable UUID advanceId) {
        return ApiResponse.success(salaryDeductionService.listForAdvance(advanceId));
    }

    @PostMapping("/cash-advances/{advanceId}")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SalaryDeductionResponse> create(@PathVariable UUID advanceId,
                                                       @RequestBody SalaryDeductionCreateRequest request) {
        return ApiResponse.success("Salary deduction request created",
                salaryDeductionService.create(advanceId, request.amount(), request.payrollMonth()));
    }

    @PostMapping("/{requestId}/retry-email")
    public ApiResponse<SalaryDeductionResponse> retryEmail(@PathVariable UUID requestId) {
        return ApiResponse.success("Request email queued again", salaryDeductionService.retryEmail(requestId));
    }

    @PostMapping("/{requestId}/cancel")
    public ApiResponse<SalaryDeductionResponse> cancel(@PathVariable UUID requestId,
                                                       @RequestBody SalaryDeductionCancelRequest request) {
        return ApiResponse.success("Salary deduction request cancelled",
                salaryDeductionService.cancel(requestId, request.reason()));
    }

    /**
     * Records what Payroll actually did. Needs a Payroll reference, or alternative evidence (type plus a file or a note).
     * The actual amount is the only amount posted to the advance balance.
     */
    @PostMapping(value = "/{requestId}/result", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SalaryDeductionResponse> recordResult(
            @PathVariable UUID requestId,
            @RequestParam BigDecimal actualAmount,
            @RequestParam String payrollMonthProcessed,
            @RequestParam(required = false) String payrollReference,
            @RequestParam(required = false) String evidenceType,
            @RequestParam(required = false) String evidenceNote,
            @RequestParam(required = false) MultipartFile evidenceFile) {
        return ApiResponse.success("Payroll result recorded",
                salaryDeductionService.recordResult(requestId, actualAmount, payrollMonthProcessed, payrollReference,
                        evidenceType, evidenceNote, evidenceFile));
    }
}
