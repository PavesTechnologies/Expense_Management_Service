-- EP03-S5: richer OCR response — subtotal, receipt time, and payment method,
-- alongside the existing merchant/date/currency/tax/total/invoice-number fields.
-- Additive only. tax_amount's *meaning* changes (CGST+SGST+IGST summed when present,
-- not just a single TAX line) but its column type is unchanged.
ALTER TABLE receipt_ocr
    ADD COLUMN receipt_time   TIME          NULL AFTER receipt_date,
    ADD COLUMN subtotal       DECIMAL(19,4) NULL AFTER currency_code,
    ADD COLUMN payment_method VARCHAR(50)   NULL AFTER invoice_number;
