package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.response.RecoveryCaseResponse;
import com.expense_management_service.service.impl.CashAdvanceRecoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import com.expense_management_service.dto.request.RecoveryCaseActionRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Recovery of pending cash advance amounts (Route 2 refund notice; Route 3 payroll report runs on schedule). */
@RestController
@RequestMapping("/xms/finance/cash-advance-recovery")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','FINANCE','FINANCE_EXECUTIVE')")
public class CashAdvanceRecoveryController {

    private final CashAdvanceRecoveryService recoveryService;

    @PostMapping("/{advanceId}/refund-notice")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RecoveryCaseResponse> startRefundNotice(@PathVariable UUID advanceId) {
        return ApiResponse.success("Refund notice queued for the employee",
                RecoveryCaseResponse.from(recoveryService.startRefundNotice(advanceId)));
    }

    @GetMapping("/cases")
    public ApiResponse<List<RecoveryCaseResponse>> openCases() {
        return ApiResponse.success(recoveryService.openCases().stream().map(RecoveryCaseResponse::from).toList());
    }

    /** Withdraws a refund notice that has not gone to payroll. The balance stays with the advance. */
    @PostMapping("/cases/{caseId}/cancel")
    public ApiResponse<RecoveryCaseResponse> cancelNotice(@PathVariable UUID caseId,
                                                          @RequestBody RecoveryCaseActionRequest request) {
        return ApiResponse.success("Refund notice cancelled",
                RecoveryCaseResponse.from(recoveryService.cancelNoticeCase(caseId, request.reason())));
    }

    /** Runs the grace-period check now, for the same work the hourly schedule does. */
    @PostMapping("/escalate")
    public ApiResponse<Integer> escalateNow() {
        return ApiResponse.success("Escalation run completed", recoveryService.escalateDueCases(LocalDateTime.now()));
    }
}
