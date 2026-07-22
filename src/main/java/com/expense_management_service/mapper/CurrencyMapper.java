package com.expense_management_service.mapper;

import com.expense_management_service.dto.request.CurrencyRequest;
import com.expense_management_service.dto.response.CurrencyResponse;
import com.expense_management_service.entity.Currency;
import org.springframework.stereotype.Component;

@Component
public class CurrencyMapper {

    public Currency toEntity(CurrencyRequest request) {
        return Currency.builder()
                .currencyCode(request.currencyCode())
                .currencyName(request.currencyName())
                .symbol(request.symbol())
                .decimalPlaces(request.decimalPlaces())
                .status(request.status())
                .build();
    }

    public void updateEntity(Currency entity, CurrencyRequest request) {
        entity.setCurrencyCode(request.currencyCode());
        entity.setCurrencyName(request.currencyName());
        entity.setSymbol(request.symbol());
        entity.setDecimalPlaces(request.decimalPlaces());
        entity.setStatus(request.status());
    }

    public CurrencyResponse toResponse(Currency entity) {
        return new CurrencyResponse(
                entity.getCurrencyId(),
                entity.getCurrencyCode(),
                entity.getCurrencyName(),
                entity.getSymbol(),
                entity.getDecimalPlaces(),
                entity.getStatus()
        );
    }
}
