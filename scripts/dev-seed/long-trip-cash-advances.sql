-- DEV ONLY. Two ₹1,000 advances for long October 2026 trips (more than 15 days), so expenses can exceed a small advance
-- and AP pays the difference. Same employee, manager, currency and cost center as past-cash-advances.sql.
-- Requested and disbursed in the past; each trip is within one month of its request date and each 30-day spending
-- window is still open on 2026-10-28. Budget tables are not changed.
-- Rollback: long-trip-cash-advances-rollback.sql.

-- Seed D: 1000 INR, requested 2026-09-30, disbursed 2026-10-01 (cash). Trip 2026-10-01 to 2026-10-17 (17 days).
INSERT INTO cash_advance (advance_id, version, employee_id, manager_id, title, purpose, notes,
    amount, base_amount, outstanding_balance, currency_id, cost_center_id, status,
    created_at, updated_at, disbursed_at, disbursement_mode, disbursement_payment_date,
    settlement_due_date, expense_deadline_reopened)
VALUES (UNHEX('f49eb90280084e29bd84c7652e771932'), 0, '5100014', '5100023',
    '[SEED] Long trip advance D', 'Seed data for long-trip testing', 'SEED 2026-10-28 D',
    1000.0000, 1000.0000, 1000.0000, UNHEX('957eac81b81d4d2c8d936bb3c979dbf4'), UNHEX('071e031ba9e84b7ab23ed53b7bdea90e'), 'IN_PROGRESS',
    '2026-09-30 10:00:00', '2026-10-01 11:00:00', '2026-10-01 11:00:00', 'CASH', '2026-10-01',
    '2026-11-15', b'0');

-- Seed E: 1000 INR, requested 2026-10-03, disbursed 2026-10-04 (UPI). Trip 2026-10-05 to 2026-10-24 (20 days).
INSERT INTO cash_advance (advance_id, version, employee_id, manager_id, title, purpose, notes,
    amount, base_amount, outstanding_balance, currency_id, cost_center_id, status,
    created_at, updated_at, disbursed_at, disbursement_mode, disbursement_payment_date,
    settlement_due_date, expense_deadline_reopened)
VALUES (UNHEX('8e0ecffb1a46495e8b7606e2cd66fd42'), 0, '5100014', '5100023',
    '[SEED] Long trip advance E', 'Seed data for long-trip testing', 'SEED 2026-10-28 E',
    1000.0000, 1000.0000, 1000.0000, UNHEX('957eac81b81d4d2c8d936bb3c979dbf4'), UNHEX('071e031ba9e84b7ab23ed53b7bdea90e'), 'IN_PROGRESS',
    '2026-10-03 10:00:00', '2026-10-04 11:00:00', '2026-10-04 11:00:00', 'UPI', '2026-10-04',
    '2026-11-15', b'0');

-- Audit history for D: created, submitted, approved by the manager, disbursed.
INSERT INTO audit_log (audit_id, action, entity_id, entity_name, new_value, old_value, performed_at, performed_by, reason, source) VALUES
(UNHEX('8d04c69a794b4943ba94e0d334abcb82'), 'CASH_ADVANCE_CREATED', UNHEX('f49eb90280084e29bd84c7652e771932'), 'CashAdvance', '{"status":"DRAFT"}', NULL, '2026-09-30 10:00:00', '5100014', 'SEED 2026-10-28 D', 'SEED'),
(UNHEX('9abd1ee744a04acb8889de0b68d3003f'), 'CASH_ADVANCE_SUBMITTED', UNHEX('f49eb90280084e29bd84c7652e771932'), 'CashAdvance', '{"status":"SUBMITTED"}', '{"status":"DRAFT"}', '2026-09-30 10:30:00', '5100014', 'SEED 2026-10-28 D', 'SEED'),
(UNHEX('6f90987d8f7f4fa4a74d6646820213b4'), 'CASH_ADVANCE_APPROVED', UNHEX('f49eb90280084e29bd84c7652e771932'), 'CashAdvance', '{"status":"APPROVED"}', '{"status":"SUBMITTED"}', '2026-09-30 17:00:00', '5100023', 'SEED 2026-10-28 D', 'SEED'),
(UNHEX('1c51665dbbaf4ee988053691ba47f70b'), 'CASH_ADVANCE_DISBURSED', UNHEX('f49eb90280084e29bd84c7652e771932'), 'CashAdvance', '{"status":"IN_PROGRESS"}', '{"status":"APPROVED"}', '2026-10-01 11:00:00', 'SEED', 'SEED 2026-10-28 D', 'SEED');

-- Audit history for E: created, submitted, approved by the manager, disbursed.
INSERT INTO audit_log (audit_id, action, entity_id, entity_name, new_value, old_value, performed_at, performed_by, reason, source) VALUES
(UNHEX('ed8535eeabc94d6598cf47c2d3fc38f8'), 'CASH_ADVANCE_CREATED', UNHEX('8e0ecffb1a46495e8b7606e2cd66fd42'), 'CashAdvance', '{"status":"DRAFT"}', NULL, '2026-10-03 10:00:00', '5100014', 'SEED 2026-10-28 E', 'SEED'),
(UNHEX('6c2e9685d1fd46e3b5b22107756ab953'), 'CASH_ADVANCE_SUBMITTED', UNHEX('8e0ecffb1a46495e8b7606e2cd66fd42'), 'CashAdvance', '{"status":"SUBMITTED"}', '{"status":"DRAFT"}', '2026-10-03 10:30:00', '5100014', 'SEED 2026-10-28 E', 'SEED'),
(UNHEX('09bcbb93abdc443cb2a38db4c96f1354'), 'CASH_ADVANCE_APPROVED', UNHEX('8e0ecffb1a46495e8b7606e2cd66fd42'), 'CashAdvance', '{"status":"APPROVED"}', '{"status":"SUBMITTED"}', '2026-10-03 17:00:00', '5100023', 'SEED 2026-10-28 E', 'SEED'),
(UNHEX('1b3a71bd18d045aaaae8a3734e52e185'), 'CASH_ADVANCE_DISBURSED', UNHEX('8e0ecffb1a46495e8b7606e2cd66fd42'), 'CashAdvance', '{"status":"IN_PROGRESS"}', '{"status":"APPROVED"}', '2026-10-04 11:00:00', 'SEED', 'SEED 2026-10-28 E', 'SEED');
