-- READ-ONLY investigation: advances that are CLOSED.
-- Purpose: explain how each advance reached CLOSED, or show that nothing explains it.
-- Nothing here writes data. Run it in a read-only session if your client supports one.

-- 1. Every CLOSED advance, its balance, and everything recorded against it.
--    Reconciliation: a closed advance is explained only if offset_total + repaid_total = amount.
SELECT LOWER(CONCAT_WS('-', SUBSTR(HEX(a.advance_id),1,8), SUBSTR(HEX(a.advance_id),9,4),
         SUBSTR(HEX(a.advance_id),13,4), SUBSTR(HEX(a.advance_id),17,4), SUBSTR(HEX(a.advance_id),21))) AS advance_uuid,
       a.amount,
       a.outstanding_balance,
       a.updated_at,
       (SELECT COUNT(*) FROM cash_advance_adjustment x WHERE x.advance_id = a.advance_id) AS offsets,
       (SELECT COALESCE(SUM(x.adjusted_amount), 0) FROM cash_advance_adjustment x WHERE x.advance_id = a.advance_id) AS offset_total,
       (SELECT COUNT(*) FROM cash_advance_repayment r WHERE r.advance_id = a.advance_id) AS repayments,
       (SELECT COALESCE(SUM(r.amount), 0) FROM cash_advance_repayment r WHERE r.advance_id = a.advance_id) AS repaid_total,
       a.amount
         - (SELECT COALESCE(SUM(x.adjusted_amount), 0) FROM cash_advance_adjustment x WHERE x.advance_id = a.advance_id)
         - (SELECT COALESCE(SUM(r.amount), 0) FROM cash_advance_repayment r WHERE r.advance_id = a.advance_id) AS unexplained_amount
FROM cash_advance a
WHERE a.status = 'CLOSED'
ORDER BY a.updated_at;

-- 2. Full audit history for each CLOSED advance (joins on the binary ID, so no text conversion is needed).
SELECT LOWER(CONCAT_WS('-', SUBSTR(HEX(a.advance_id),1,8), SUBSTR(HEX(a.advance_id),9,4),
         SUBSTR(HEX(a.advance_id),13,4), SUBSTR(HEX(a.advance_id),17,4), SUBSTR(HEX(a.advance_id),21))) AS advance_uuid,
       al.entity_name, al.action, al.old_value, al.new_value, al.performed_by, al.performed_at, al.reason
FROM audit_log al
JOIN cash_advance a ON HEX(a.advance_id) = HEX(al.entity_id)
WHERE a.status = 'CLOSED'
ORDER BY a.updated_at, al.performed_at;

-- 3. Any audit row at all in the suspect window (catches closures recorded under other entity names).
SELECT entity_name, HEX(entity_id) AS entity_hex, action, old_value, new_value, performed_by, performed_at
FROM audit_log
WHERE performed_at BETWEEN '2026-09-21 00:00:00' AND '2026-10-07 00:00:00'
  AND (new_value LIKE '%CLOSED%' OR action LIKE '%CLOSE%' OR action LIKE '%REPAY%' OR action LIKE '%ADJUST%')
ORDER BY performed_at;

-- 4. Is the general query log on? If it is, it records every statement and the connection it came from.
SHOW VARIABLES LIKE 'general_log%';
SHOW VARIABLES LIKE 'log_output';

-- 5. Binary log coverage: the first event in the oldest file shows how far back the log reaches.
SHOW BINARY LOGS;
SHOW BINLOG EVENTS IN 'binlog.004196' LIMIT 5;
