-- Notification Center. The notification table was created by ddl-auto=update as a bare
-- (employee_id, title, message, is_read) store with nothing writing to it; it now backs
-- workflow-generated notifications.
--
-- Two kinds of recipient:
--   * personal  - employee_id set (the report owner, an assigned approver); read state in is_read
--   * team inbox - recipient_role set (FINANCE_EXECUTIVE / AP_EXECUTIVE / ADMIN), seen by everyone
--                  holding that role; read state is per person, in notification_read
ALTER TABLE notification
    MODIFY COLUMN employee_id VARCHAR(255) NULL,
    MODIFY COLUMN message TEXT NULL,
    ADD COLUMN recipient_role VARCHAR(64) NULL,
    ADD COLUMN category VARCHAR(32) NULL,
    ADD COLUMN event_type VARCHAR(64) NULL,
    ADD COLUMN report_id BINARY(16) NULL,
    ADD COLUMN report_number VARCHAR(64) NULL,
    ADD COLUMN actor_name VARCHAR(255) NULL,
    ADD COLUMN amount DECIMAL(19, 4) NULL,
    ADD COLUMN currency_code VARCHAR(8) NULL,
    ADD COLUMN status_label VARCHAR(128) NULL,
    ADD COLUMN action_label VARCHAR(64) NULL,
    ADD COLUMN link VARCHAR(512) NULL;

CREATE INDEX idx_notification_employee_sent ON notification (employee_id, sent_at);
CREATE INDEX idx_notification_role_sent ON notification (recipient_role, sent_at);
CREATE INDEX idx_notification_dedupe ON notification (event_type, report_id, sent_at);

CREATE TABLE IF NOT EXISTS notification_read (
    notification_id BINARY(16)   NOT NULL,
    employee_id     VARCHAR(255) NOT NULL,
    read_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (notification_id, employee_id),
    CONSTRAINT fk_notification_read_notification
        FOREIGN KEY (notification_id) REFERENCES notification (notification_id) ON DELETE CASCADE
);
