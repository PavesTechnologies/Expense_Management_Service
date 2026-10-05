package com.expense_management_service.enums;

/**
 * A levied part of a tax code. A controlled list so reports can group by component across codes
 * (all CGST, all cess ...). The calculation never branches on it - it only sums component rates.
 */
public enum TaxComponentCode {
    CGST,
    SGST,
    UTGST,
    IGST,
    CESS,
    VAT,
    SALES_TAX,
    OTHER
}
