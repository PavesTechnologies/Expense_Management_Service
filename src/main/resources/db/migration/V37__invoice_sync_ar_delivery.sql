-- Delivery of handed-off client-billable expenses to AR over RabbitMQ (invoice_sync = one handoff).
--
-- Columns (Hibernate ddl-auto=update adds these too; each is guarded so this is safe either way):
--   ar_delivery_status: PENDING / SENT / FAILED - varchar, not a MySQL ENUM. NULL for handoffs recorded
--       before delivery existed (AR pulled those); Finance can resend them.
--   ar_delivery_attempts, ar_last_attempt_at: attempts so far - the retry job stops at the configured limit.
--   ar_delivered_at: when the broker confirmed the message onto AR's queue.
--   ar_delivery_error: why the last attempt failed; cleared once delivered.

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'invoice_sync' AND COLUMN_NAME = 'ar_delivery_status') = 0,
               'ALTER TABLE invoice_sync ADD COLUMN ar_delivery_status VARCHAR(16) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'invoice_sync' AND COLUMN_NAME = 'ar_delivery_attempts') = 0,
               'ALTER TABLE invoice_sync ADD COLUMN ar_delivery_attempts INT NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'invoice_sync' AND COLUMN_NAME = 'ar_last_attempt_at') = 0,
               'ALTER TABLE invoice_sync ADD COLUMN ar_last_attempt_at DATETIME(6) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'invoice_sync' AND COLUMN_NAME = 'ar_delivered_at') = 0,
               'ALTER TABLE invoice_sync ADD COLUMN ar_delivered_at DATETIME(6) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;

SET @ddl := IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME = 'invoice_sync' AND COLUMN_NAME = 'ar_delivery_error') = 0,
               'ALTER TABLE invoice_sync ADD COLUMN ar_delivery_error VARCHAR(500) NULL', 'DO 0');
PREPARE ddl_stmt FROM @ddl; EXECUTE ddl_stmt; DEALLOCATE PREPARE ddl_stmt;
