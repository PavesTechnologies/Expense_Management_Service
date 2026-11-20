-- DEV ONLY. Removes exactly the five advances created by after-trip-cash-advances.sql and their audit rows.
-- Matches by fixed advance_id, so no other advance is touched. Delete any expense reports / adjustments you created
-- against these advances first, or the cash_advance delete will fail on foreign keys.

START TRANSACTION;

DELETE FROM audit_log
WHERE entity_name = 'CashAdvance'
  AND reason LIKE 'SEED-AFTERTRIP %';

DELETE FROM cash_advance
WHERE advance_id IN (
    UNHEX('fcc23f1bbd9c4af6927b63395cfcc008'),
    UNHEX('af37e9a9ddd54d2d998b8e32036e968a'),
    UNHEX('36b2d34e45264b4782879d1aec104054'),
    UNHEX('a6b24849109242f4aa680c0bf735362a'),
    UNHEX('dc635366409f45feb093077041ba7dfd'));

COMMIT;
