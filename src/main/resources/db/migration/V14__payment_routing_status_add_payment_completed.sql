-- expense_report.payment_routing_status was never created/altered by a Flyway migration - it was
-- generated purely by hibernate.ddl-auto=update from ExpenseReport.paymentRoutingStatus
-- (@Enumerated(EnumType.STRING)). On MySQL, Hibernate's dialect emits a native SQL ENUM(...) for
-- that mapping, snapshotting whichever PaymentRoutingStatus constants existed at the moment the
-- column was first created. ddl-auto=update never widens an existing column's ENUM literal list
-- when the Java enum later gains a new constant, so the live column drifted to:
--   enum('APPROVED_FOR_PAYMENT','HANDOFF_FAILED','INVOICE_HANDOFF_COMPLETED',
--        'INVOICE_HANDOFF_PENDING','NONE')
-- missing PAYMENT_COMPLETED entirely (confirmed via information_schema.COLUMNS). Every
-- ApPaymentServiceImpl.markPaymentCompleted() call was failing with
-- "java.sql.SQLException: Data truncated for column 'payment_routing_status'" - MySQL's strict-mode
-- error for a value outside an ENUM column's literal list - so the report's payment_routing_status
-- stayed at APPROVED_FOR_PAYMENT and paymentCompletedBy/paymentCompletedAt were never set.
--
-- Widens the enum in place, preserving every existing value plus the missing one, so already-stored
-- rows in every current state (including NONE, per the applyPaymentRouting flow-config gap) keep
-- deserializing correctly and no data is lost or reinterpreted.
ALTER TABLE expense_report
    MODIFY COLUMN payment_routing_status
        ENUM('NONE', 'APPROVED_FOR_PAYMENT', 'PAYMENT_COMPLETED', 'INVOICE_HANDOFF_PENDING',
             'INVOICE_HANDOFF_COMPLETED', 'HANDOFF_FAILED') NULL;
