package com.expense_management_service.dto.response;

import java.time.LocalDateTime;

public record ExchangeRateRefreshResponse(
        int pairsProcessed,
        int ratesCreated,
        int ratesSkipped,
        LocalDateTime executedAt,
        String note
) {
}
