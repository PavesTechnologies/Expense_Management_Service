package com.expense_management_service.enums;

/**
 * How an expense tax code's rate is levied. GST in India is either split equally between the
 * Centre and the State (intra-state purchase: CGST + SGST) or charged as a single integrated tax
 * (inter-state: IGST); VAT covers overseas receipts. The rate on a {@code TaxCode} is always the
 * combined rate — e.g. CGST_SGST at 18% means 9% + 9% — and equals the sum of its components.
 * <p>
 * The type is the structure family and supplies the component template; the arithmetic never
 * depends on it. Phase 1 offers the India set only (CGST_SGST, IGST, EXEMPT, and OTHER for GST +
 * compensation cess); VAT and SALES_TAX stay in the model for later configuration.
 */
public enum TaxType {
    CGST_SGST,
    IGST,
    VAT,
    EXEMPT,
    /** Reserved: state / county sales tax. Not offered yet. */
    SALES_TAX,
    /** Admin-defined components, e.g. GST 28% + compensation cess 12%. */
    OTHER
}
