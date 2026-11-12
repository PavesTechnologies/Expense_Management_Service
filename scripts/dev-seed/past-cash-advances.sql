-- DEV ONLY. Seeds three cash advances that were requested, approved and disbursed in the past,
-- so the expense flow can be tested on 2026-10-28. Do not run against production.
--
-- Employee: Sindhu Yanala, employee_id 5100014, manager 5100023 (from employee_cache).
-- Each advance is IN_PROGRESS (the app's approved-and-disbursed state) with disbursed_at in the past.
-- settlement_due_date is 2026-11-15 so the overdue scheduler does not flip these advances to OVERDUE.
-- Budget tables are NOT changed. Rollback: past-cash-advances-rollback.sql.

-- Seed A: client visit, 5000 INR. Requested 10 Oct, disbursed 12 Oct. Trip 15-20 Oct.
INSERT INTO cash_advance (advance_id, version, employee_id, manager_id, title, purpose, notes,
    amount, base_amount, outstanding_balance, currency_id, cost_center_id, status,
    created_at, updated_at, disbursed_at, disbursement_mode, disbursement_payment_date,
    settlement_due_date, expense_deadline_reopened)
VALUES (UNHEX('84727c347b844e6db76da841a494123b'), 0, '5100014', '5100023',
    '[SEED] Client visit advance', 'Seed data for date-rule testing', 'SEED 2026-10-28 A',
    5000.0000, 5000.0000, 5000.0000, UNHEX('957eac81b81d4d2c8d936bb3c979dbf4'), UNHEX('071e031ba9e84b7ab23ed53b7bdea90e'), 'IN_PROGRESS',
    '2026-10-10 10:00:00', '2026-10-12 11:00:00', '2026-10-12 11:00:00', 'CASH', '2026-10-12',
    '2026-11-15', b'0');

-- Seed B: workshop, 10000 INR. Requested 8 Oct, disbursed 9 Oct. Trip 12-16 Oct.
INSERT INTO cash_advance (advance_id, version, employee_id, manager_id, title, purpose, notes,
    amount, base_amount, outstanding_balance, currency_id, cost_center_id, status,
    created_at, updated_at, disbursed_at, disbursement_mode, disbursement_payment_date,
    settlement_due_date, expense_deadline_reopened)
VALUES (UNHEX('be41b100177e401a9c533bb2c08d2e4b'), 0, '5100014', '5100023',
    '[SEED] Workshop advance', 'Seed data for date-rule testing', 'SEED 2026-10-28 B',
    10000.0000, 10000.0000, 10000.0000, UNHEX('957eac81b81d4d2c8d936bb3c979dbf4'), UNHEX('071e031ba9e84b7ab23ed53b7bdea90e'), 'IN_PROGRESS',
    '2026-10-08 10:00:00', '2026-10-09 11:00:00', '2026-10-09 11:00:00', 'UPI', '2026-10-09',
    '2026-11-15', b'0');

-- Seed C: audit trip, 8000 INR. Requested 15 Oct, disbursed 16 Oct. Trip 20-25 Oct.
INSERT INTO cash_advance (advance_id, version, employee_id, manager_id, title, purpose, notes,
    amount, base_amount, outstanding_balance, currency_id, cost_center_id, status,
    created_at, updated_at, disbursed_at, disbursement_mode, disbursement_payment_date,
    settlement_due_date, expense_deadline_reopened)
VALUES (UNHEX('7243315f85d4435694b38ca7239ffaa9'), 0, '5100014', '5100023',
    '[SEED] Audit trip advance', 'Seed data for date-rule testing', 'SEED 2026-10-28 C',
    8000.0000, 8000.0000, 8000.0000, UNHEX('957eac81b81d4d2c8d936bb3c979dbf4'), UNHEX('071e031ba9e84b7ab23ed53b7bdea90e'), 'IN_PROGRESS',
    '2026-10-15 10:00:00', '2026-10-16 11:00:00', '2026-10-16 11:00:00', 'BANK_TRANSFER', '2026-10-16',
    '2026-11-15', b'0');

-- Audit history: created, submitted, approved (by the manager), disbursed, for each advance.
INSERT INTO audit_log (audit_id, action, entity_id, entity_name, new_value, old_value, performed_at, performed_by, reason, source) VALUES
(UNHEX('0990ebfdfe7f4ec8a898cabc57d90b4a'), 'CASH_ADVANCE_CREATED', UNHEX('84727c347b844e6db76da841a494123b'), 'CashAdvance', '{"status":"DRAFT"}', NULL, '2026-10-10 10:00:00', '5100014', 'SEED 2026-10-28 A', 'SEED'),
(UNHEX('0208077da01b47ffb121f5ca918f95e4'), 'CASH_ADVANCE_SUBMITTED', UNHEX('84727c347b844e6db76da841a494123b'), 'CashAdvance', '{"status":"SUBMITTED"}', '{"status":"DRAFT"}', '2026-10-10 10:30:00', '5100014', 'SEED 2026-10-28 A', 'SEED'),
(UNHEX('984c6f329a8a4e788bb057585c212c65'), 'CASH_ADVANCE_APPROVED', UNHEX('84727c347b844e6db76da841a494123b'), 'CashAdvance', '{"status":"APPROVED"}', '{"status":"SUBMITTED"}', '2026-10-11 09:00:00', '5100023', 'SEED 2026-10-28 A', 'SEED'),
(UNHEX('1cb39b6edaa64c84affd9efc54de02f1'), 'CASH_ADVANCE_DISBURSED', UNHEX('84727c347b844e6db76da841a494123b'), 'CashAdvance', '{"status":"IN_PROGRESS"}', '{"status":"APPROVED"}', '2026-10-12 11:00:00', 'SEED', 'SEED 2026-10-28 A', 'SEED'),
(UNHEX('0f49cdbeae56415eaddf617e7c5e6067'), 'CASH_ADVANCE_CREATED', UNHEX('be41b100177e401a9c533bb2c08d2e4b'), 'CashAdvance', '{"status":"DRAFT"}', NULL, '2026-10-08 10:00:00', '5100014', 'SEED 2026-10-28 B', 'SEED'),
(UNHEX('b77219a4da71497987ec01621107f7b9'), 'CASH_ADVANCE_SUBMITTED', UNHEX('be41b100177e401a9c533bb2c08d2e4b'), 'CashAdvance', '{"status":"SUBMITTED"}', '{"status":"DRAFT"}', '2026-10-08 10:30:00', '5100014', 'SEED 2026-10-28 B', 'SEED'),
(UNHEX('1834959934a14b37952ed20085f726ea'), 'CASH_ADVANCE_APPROVED', UNHEX('be41b100177e401a9c533bb2c08d2e4b'), 'CashAdvance', '{"status":"APPROVED"}', '{"status":"SUBMITTED"}', '2026-10-08 16:00:00', '5100023', 'SEED 2026-10-28 B', 'SEED'),
(UNHEX('0c3391e3b3e74527b2bb09a7b7abd659'), 'CASH_ADVANCE_DISBURSED', UNHEX('be41b100177e401a9c533bb2c08d2e4b'), 'CashAdvance', '{"status":"IN_PROGRESS"}', '{"status":"APPROVED"}', '2026-10-09 11:00:00', 'SEED', 'SEED 2026-10-28 B', 'SEED'),
(UNHEX('06e62d64901446ae8097d00fd88b6fe4'), 'CASH_ADVANCE_CREATED', UNHEX('7243315f85d4435694b38ca7239ffaa9'), 'CashAdvance', '{"status":"DRAFT"}', NULL, '2026-10-15 10:00:00', '5100014', 'SEED 2026-10-28 C', 'SEED'),
(UNHEX('ef6951f8a728439faa712d04409fcde3'), 'CASH_ADVANCE_SUBMITTED', UNHEX('7243315f85d4435694b38ca7239ffaa9'), 'CashAdvance', '{"status":"SUBMITTED"}', '{"status":"DRAFT"}', '2026-10-15 10:30:00', '5100014', 'SEED 2026-10-28 C', 'SEED'),
(UNHEX('c607c530dc4c44f4b43587552037784a'), 'CASH_ADVANCE_APPROVED', UNHEX('7243315f85d4435694b38ca7239ffaa9'), 'CashAdvance', '{"status":"APPROVED"}', '{"status":"SUBMITTED"}', '2026-10-15 17:00:00', '5100023', 'SEED 2026-10-28 C', 'SEED'),
(UNHEX('64615ea35b2c45689346639678ec6b28'), 'CASH_ADVANCE_DISBURSED', UNHEX('7243315f85d4435694b38ca7239ffaa9'), 'CashAdvance', '{"status":"IN_PROGRESS"}', '{"status":"APPROVED"}', '2026-10-16 11:00:00', 'SEED', 'SEED 2026-10-28 C', 'SEED');
