package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.TaxCodeRequest;
import com.expense_management_service.dto.response.TaxCodeResponse;
import com.expense_management_service.entity.TaxCode;
import org.springframework.stereotype.Component;

@Component
public class TaxCodeMapper {

    public TaxCode toEntity(TaxCodeRequest request) {
        TaxCode entity = new TaxCode();
        updateEntity(entity, request);
        return entity;
    }

    /** Everything except {@code inputTaxGlAccount}, which the service resolves and validates. */
    public void updateEntity(TaxCode entity, TaxCodeRequest request) {
        entity.setTaxCode(request.taxCode().trim().toUpperCase());
        entity.setTaxName(request.taxName().trim());
        entity.setTaxType(request.taxType());
        entity.setRatePercent(request.ratePercent());
        entity.setItcEligible(Boolean.TRUE.equals(request.itcEligible()));
        entity.setDescription(request.description());
        entity.setEffectiveFrom(request.effectiveFrom());
        entity.setEffectiveTo(request.effectiveTo());
        entity.setStatus(request.status());
    }

    public TaxCodeResponse toResponse(TaxCode entity, long mappedCategoryCount) {
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
                mappedCategoryCount
        );
    }
}
