package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.ExchangeRateRequest;
import com.expense_management_service.dto.response.ExchangeRateResponse;
import com.expense_management_service.entity.ExchangeRate;
import org.springframework.stereotype.Component;

@Component
public class ExchangeRateMapper {

    public ExchangeRate toEntity(ExchangeRateRequest request) {
        return ExchangeRate.builder()
                .rate(request.rate())
                .effectiveDate(request.effectiveDate())
                .source(request.source())
                .build();
    }

    public void updateEntity(ExchangeRate entity, ExchangeRateRequest request) {
        entity.setRate(request.rate());
        entity.setEffectiveDate(request.effectiveDate());
        entity.setSource(request.source());
    }

    public ExchangeRateResponse toResponse(ExchangeRate entity) {
        return new ExchangeRateResponse(
                entity.getExchangeRateId(),
                entity.getFromCurrency() != null ? entity.getFromCurrency().getCurrencyId() : null,
                entity.getFromCurrency() != null ? entity.getFromCurrency().getCurrencyCode() : null,
                entity.getToCurrency() != null ? entity.getToCurrency().getCurrencyId() : null,
                entity.getToCurrency() != null ? entity.getToCurrency().getCurrencyCode() : null,
                entity.getRate(),
                entity.getEffectiveDate(),
                entity.getSource(),
                entity.getCreatedAt()
        );
    }
}
