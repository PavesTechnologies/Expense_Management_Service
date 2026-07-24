

package com.expense_management_service.dto.external;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Maps the JSON payload returned by the live Exchange Rate API's
 * {@code GET /{apiKey}/latest/{baseCurrency}} endpoint (both the success and
 * {@code "result": "error"} shapes share this one type).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExchangeRateApiResponse(
        String result,
        @JsonProperty("error-type") String errorType,
        @JsonProperty("base_code") String baseCode,
        @JsonProperty("time_last_update_utc") String timeLastUpdateUtc,
        @JsonProperty("conversion_rates") Map<String, BigDecimal> conversionRates
) {
}
