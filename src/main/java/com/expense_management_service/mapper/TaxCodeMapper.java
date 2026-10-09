package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.TaxCodeRequest;
import com.expense_management_service.dto.response.TaxCodeComponentResponse;
import com.expense_management_service.dto.response.TaxCodeResponse;
import com.expense_management_service.entity.TaxCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TaxCodeMapper {

    public TaxCode toEntity(TaxCodeRequest request) {
        TaxCode entity = new TaxCode();
        entity.setCountryCode("IN");
        entity.setItcRecoverablePercent(java.math.BigDecimal.ZERO);
        if (entity.getComponents() == null) {
            entity.setComponents(new java.util.ArrayList<>());
        }
        updateEntity(entity, request);
        return entity;
    }

    /**
     * Plain fields only. The service owns components, the derived rate, ITC %, country,
     * {@code inputTaxGlAccount} and status defaulting.
     */
    public void updateEntity(TaxCode entity, TaxCodeRequest request) {
        entity.setTaxCode(request.taxCode().trim().toUpperCase());
        entity.setTaxName(request.taxName().trim());
        entity.setTaxType(request.taxType());
        entity.setDescription(request.description());
        entity.setEffectiveFrom(request.effectiveFrom());
        entity.setEffectiveTo(request.effectiveTo());
        entity.setRegionCode(StringUtils.hasText(request.regionCode()) ? request.regionCode().trim().toUpperCase() : null);
        // No status on the request keeps the current one; otherwise an update that omits it would
        // silently re-activate a deactivated code (the service defaults a new code to ACTIVE).
        if (request.status() != null && !request.status().isBlank()) {
            entity.setStatus(request.status().trim().toUpperCase());
        }
    }

    public TaxCodeResponse toResponse(TaxCode entity, long mappedCategoryCount, long usageCount) {
        var gl = entity.getInputTaxGlAccount();
        return new TaxCodeResponse(
                entity.getTaxCodeId(),
                entity.getTaxCode(),
                entity.getTaxName(),
                entity.getTaxType() != null ? entity.getTaxType().name() : null,
                entity.getRatePercent(),
                entity.getItcEligible(),
                gl != null ? gl.getGlAccountId() : null,
                gl != null ? gl.getGlAccountName() : null,
                entity.getDescription(),
                entity.getEffectiveFrom(),
                entity.getEffectiveTo(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                mappedCategoryCount,
                entity.getCountryCode(),
                entity.getRegionCode(),
                entity.getItcRecoverablePercent(),
                entity.getComponents().stream()
                        .map(c -> new TaxCodeComponentResponse(
                                c.getComponentId(),
                                c.getComponentCode().name(),
                                c.getLabel(),
                                c.getRatePercent(),
                                c.getSequence(),
                                c.getGlAccount() != null ? c.getGlAccount().getGlAccountId() : null,
                                c.getGlAccount() != null ? c.getGlAccount().getGlAccountName() : null))
                        .toList(),
                usageCount,
                usageCount > 0
        );
    }
}
