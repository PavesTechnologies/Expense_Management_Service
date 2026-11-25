-- DEV ONLY. Removes exactly the five advances created by past-trip-future-settlement-cash-advances.sql and their
-- audit rows. Delete any expense reports / adjustments created against these advances first.

START TRANSACTION;

DELETE FROM audit_log
WHERE entity_name = 'CashAdvance'
  AND reason LIKE 'SEED-1124 %';

DELETE FROM cash_advance
WHERE advance_id IN (
    UNHEX('71e17ea545d34b5ba993b52fb3a3138a'),
    UNHEX('723b46ee28ab4b4b921e378c7a9af570'),
    UNHEX('bfe1fa4c2f7d40e7865912e302cad2b2'),
    UNHEX('993ca0e313b644578913f32bcbe59780'),
    UNHEX('e11796d6bfb640c5bd449010ef2785d5'));

COMMIT;
