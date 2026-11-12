package com.expense_management_service.controller;

import com.expense_management_service.common.ApiResponse;
import com.expense_management_service.dto.response.AuditLogResponse;
import com.expense_management_service.mapper.AuditLogMapper;
import com.expense_management_service.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tax audit history for one record (design §19 / Phase 7): a tax code, a category tax mapping, or
 * an expense line's tax (overrides, OCR evidence, freezes, Finance adjustments). Newest first.
 */
@RestController
@RequiredArgsConstructor
public class TaxAuditController {

    /** Path segment -> audit_log.entity_name. Only tax-related records are exposed here. */
    private static final Map<String, String> ENTITIES = Map.of(
            "tax-codes", "TaxCode",
            "tax-mappings", "ExpenseCategoryTaxMapping",
            "line-items", "ExpenseLineItem");

    private final AuditLogRepository auditLogRepository;
    private final AuditLogMapper auditLogMapper;

    @GetMapping("/xms/tax/audit/{entity}/{entityId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN','FINANCE','FINANCE_EXECUTIVE')")
    @Transactional(readOnly = true)
    public ApiResponse<List<AuditLogResponse>> history(@PathVariable String entity, @PathVariable UUID entityId) {
        String entityName = ENTITIES.get(entity);
        if (entityName == null) {
            throw new IllegalArgumentException("entity must be one of " + ENTITIES.keySet());
        }
        return ApiResponse.success(auditLogRepository.findTop200ByEntityNameAndEntityIdOrderByPerformedAtDesc(entityName, entityId).stream()
                // A line's audit trail also has non-tax entries; keep the tax story only.
                .filter(a -> !"ExpenseLineItem".equals(entityName) || a.getAction().startsWith("TAX_") || a.getAction().startsWith("OCR_TAX"))
                .map(auditLogMapper::toResponse)
                .toList());
    }
}
