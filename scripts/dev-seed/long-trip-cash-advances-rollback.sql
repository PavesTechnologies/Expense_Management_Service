-- DEV ONLY. Removes exactly the two long-trip advances from long-trip-cash-advances.sql and their audit rows.
-- Matched by fixed ids. Run reconcile-approved-report-rollback.sql first, if the reconciliation is still applied.

DELETE FROM audit_log WHERE audit_id IN (
    UNHEX('8d04c69a794b4943ba94e0d334abcb82'), UNHEX('9abd1ee744a04acb8889de0b68d3003f'),
    UNHEX('6f90987d8f7f4fa4a74d6646820213b4'), UNHEX('1c51665dbbaf4ee988053691ba47f70b'),
    UNHEX('ed8535eeabc94d6598cf47c2d3fc38f8'), UNHEX('6c2e9685d1fd46e3b5b22107756ab953'),
    UNHEX('09bcbb93abdc443cb2a38db4c96f1354'), UNHEX('1b3a71bd18d045aaaae8a3734e52e185'));

DELETE FROM cash_advance WHERE advance_id IN (
    UNHEX('f49eb90280084e29bd84c7652e771932'),
    UNHEX('8e0ecffb1a46495e8b7606e2cd66fd42'));
