-- Tax Configuration master (TaxCode entity). expense_category.tax_code was free text; it now
-- references tax_code.tax_code by value (validated on category save), and the code's rate
-- pre-fills GST on expense line items. Created here rather than by ddl-auto=update because Flyway
-- always runs first (see V18) and the seed rows below need the table to exist.
--
-- IF NOT EXISTS / INSERT IGNORE: with spring-boot-devtools, a hot restart can load the new TaxCode
-- entity before this file reaches the classpath, so ddl-auto=update creates tax_code first and a
-- plain CREATE TABLE here then fails ("already exists"), leaving V20 recorded as failed. This makes
-- the migration safe to run against a table Hibernate already created, and safe to re-run.
CREATE TABLE IF NOT EXISTS tax_code (
    tax_code_id             BINARY(16)    NOT NULL,
    tax_code                VARCHAR(50)   NOT NULL,
    tax_name                VARCHAR(255)  NOT NULL,
    tax_type                VARCHAR(32)   NOT NULL,
    rate_percent            DECIMAL(5, 2) NOT NULL,
    itc_eligible            BIT(1)        NOT NULL,
    input_tax_gl_account_id BINARY(16)    NULL,
    description             LONGTEXT      NULL,
    effective_from          DATE          NOT NULL,
    effective_to            DATE          NULL,
    status                  VARCHAR(32)   NOT NULL,
    created_at              DATETIME(6)   NULL,
    updated_at              DATETIME(6)   NULL,
    PRIMARY KEY (tax_code_id),
    CONSTRAINT uk_tax_code_tax_code UNIQUE (tax_code),
    CONSTRAINT fk_tax_code_input_tax_gl_account
        FOREIGN KEY (input_tax_gl_account_id) REFERENCES gl_account (gl_account_id)
);

-- Standard Indian GST slabs as a starting point; admins edit/extend them in Tax Configuration.
-- itc_eligible defaults to FALSE: many common expense types (food, travel for personal use, etc.)
-- are blocked credits, so Finance should switch it on deliberately per code.
INSERT IGNORE INTO tax_code (tax_code_id, tax_code, tax_name, tax_type, rate_percent, itc_eligible,
                      description, effective_from, status, created_at, updated_at)
VALUES
    (UUID_TO_BIN(UUID()), 'EXEMPT', 'Exempt / Nil rated', 'EXEMPT', 0.00, b'0',
     'No GST charged', '2017-07-01', 'ACTIVE', NOW(6), NOW(6)),
    (UUID_TO_BIN(UUID()), 'GST5', 'GST 5% (CGST 2.5% + SGST 2.5%)', 'CGST_SGST', 5.00, b'0',
     'Intra-state purchase', '2017-07-01', 'ACTIVE', NOW(6), NOW(6)),
    (UUID_TO_BIN(UUID()), 'GST12', 'GST 12% (CGST 6% + SGST 6%)', 'CGST_SGST', 12.00, b'0',
     'Intra-state purchase', '2017-07-01', 'ACTIVE', NOW(6), NOW(6)),
    (UUID_TO_BIN(UUID()), 'GST18', 'GST 18% (CGST 9% + SGST 9%)', 'CGST_SGST', 18.00, b'0',
     'Intra-state purchase', '2017-07-01', 'ACTIVE', NOW(6), NOW(6)),
    (UUID_TO_BIN(UUID()), 'GST28', 'GST 28% (CGST 14% + SGST 14%)', 'CGST_SGST', 28.00, b'0',
     'Intra-state purchase', '2017-07-01', 'ACTIVE', NOW(6), NOW(6)),
    (UUID_TO_BIN(UUID()), 'IGST18', 'IGST 18%', 'IGST', 18.00, b'0',
     'Inter-state purchase (e.g. flights, out-of-state hotels)', '2017-07-01', 'ACTIVE', NOW(6), NOW(6));
