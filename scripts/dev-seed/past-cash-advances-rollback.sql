-- DEV ONLY. Removes exactly the three advances created by past-cash-advances.sql and their audit rows.
-- Matches by fixed advance_id, so no other data is touched.

DELETE FROM audit_log
WHERE entity_name = 'CashAdvance'
  AND reason LIKE 'SEED 2026-10-28%';

DELETE FROM cash_advance
WHERE advance_id IN (
    UNHEX('84727c347b844e6db76da841a494123b'),
    UNHEX('be41b100177e401a9c533bb2c08d2e4b'),
    UNHEX('7243315f85d4435694b38ca7239ffaa9'));
