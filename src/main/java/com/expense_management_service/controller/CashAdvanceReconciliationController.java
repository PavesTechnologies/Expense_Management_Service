package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.service.impl.CashAdvanceReconciliationService;
import com.expense_management_service.service.impl.CashAdvanceReconciliationService.RunView;
import com.expense_management_service.service.impl.CashAdvanceReconciliationService.Snapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Finance reconciliation of cash advance balances. Every endpoint is Finance-only. The snapshot is read-only; runs store
 * results and never overwrite a cash advance balance. Financial-year periods stay unavailable until Finance confirms
 * the financial-year start ({@code expense.finance-year-start-month}).
 */
@RestController
@RequestMapping("/xms/finance/reconciliation")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','FINANCE','FINANCE_EXECUTIVE')")
public class CashAdvanceReconciliationController {

    private final CashAdvanceReconciliationService reconciliationService;

    @GetMapping("/snapshot")
    public ApiResponse<Snapshot> snapshot(@RequestParam String periodType,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate anchorDate) {
        return ApiResponse.success(reconciliationService.snapshot(periodType, anchorDate));
    }

    @PostMapping(value = "/runs", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RunView> createRun(@Valid @RequestBody CreateRunRequest request) {
        return ApiResponse.success("Reconciliation run created",
                reconciliationService.createRun(request.periodType(), request.anchorDate()));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<RunView> getRun(@PathVariable UUID runId) {
        return ApiResponse.success(reconciliationService.getRun(runId));
    }

    @PostMapping(value = "/runs/{runId}/lines/{lineId}/resolve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<RunView> resolveLine(@PathVariable UUID runId, @PathVariable UUID lineId,
                                            @Valid @RequestBody ResolveLineRequest request) {
        return ApiResponse.success("Line resolution recorded",
                reconciliationService.resolveLine(runId, lineId, request.note()));
    }

    @PostMapping("/runs/{runId}/submit")
    public ApiResponse<RunView> submit(@PathVariable UUID runId) {
        return ApiResponse.success("Reconciliation submitted", reconciliationService.submit(runId));
    }

    @PostMapping("/runs/{runId}/approve")
    public ApiResponse<RunView> approve(@PathVariable UUID runId) {
        return ApiResponse.success("Reconciliation approved", reconciliationService.approve(runId));
    }

    @PostMapping("/runs/{runId}/close")
    public ApiResponse<RunView> close(@PathVariable UUID runId) {
        return ApiResponse.success("Reconciliation period closed", reconciliationService.close(runId));
    }

    public record CreateRunRequest(
            @NotBlank String periodType,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate anchorDate
    ) {
    }

    public record ResolveLineRequest(
            @NotBlank @Size(max = 500) String note
    ) {
    }
}
