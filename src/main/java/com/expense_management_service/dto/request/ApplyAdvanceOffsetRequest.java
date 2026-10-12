package com.expense_management_service.dto.request;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Body for applying a verified expense report against its cash advance.
 *
 * @param cashAdvanceId optional; when given, must match the advance the report is linked to
 * @param offsetAmount  optional; when null, the largest allowed amount is applied (the lower of the
 *                      report's verified total and the advance's outstanding balance)
 */
public record ApplyAdvanceOffsetRequest(UUID cashAdvanceId, BigDecimal offsetAmount) {
}
