package com.expense_management_service.dto.ocr;

import java.math.BigDecimal;

/** One tax component read off a receipt, e.g. CGST 900.00. Code is CGST, SGST, UTGST, IGST or CESS. */
public record OcrTaxComponent(String code, BigDecimal amount) {
}
