-- DEV ONLY. Seeds five more cash advances for Sindhu Yanala (5100014) on 2026-11-24: approved (manager -> Finance),
-- disbursed, trips already over, settlement due dates in the future. Do not run against production.
--
--   * Status IN_PROGRESS (approved and disbursed, linkable to an expense report).
--   * Every trip ends on or after 2026-10-27, so the 30-day expense window (trip end + 30 days) is still open on
--     2026-11-24 and the advance is not locked / OVERDUE.
--   * settlement_due_date is Dec 2026 - Jan 2027.
--   * Cost center budget is NOT charged (budget_fiscal_year stays NULL).
-- Rollback: past-trip-future-settlement-cash-advances-rollback.sql.

SET @emp      = '5100014';
SET @mgr      = '5100023';
SET @finance  = 'SEED-FINANCE';
SET @currency = UNHEX('957eac81b81d4d2c8d936bb3c979dbf4'); -- INR
SET @cc       = UNHEX('071e031ba9e84b7ab23ed53b7bdea90e'); -- Eng

START TRANSACTION;

INSERT INTO cash_advance (advance_id, version, advance_number, employee_id, manager_id, title, purpose, notes,
    amount, requested_amount, approved_amount, disbursed_amount, base_amount, outstanding_balance,
    carried_in_amount, carried_out_amount, currency_id, cost_center_id, status, request_type,
    outstanding_amount_snapshot, total_advance_allocation, employee_justification,
    needed_by_date, settlement_due_date, expected_trip_start_date, expected_trip_end_date,
    recommended_treatment, recommended_amount, manager_justification, manager_decided_by, manager_decided_at,
    disbursed_at, disbursement_mode, disbursement_payment_date, disbursement_reference,
    disbursement_transfer_details, disbursement_payee_upi_id, disbursement_acknowledged_by,
    disbursement_confirmation_note, expense_deadline_reopened, created_at, updated_at)
VALUES
-- K: Hyderabad client workshop, 6000 INR, cash. Trip 27-31 Oct. Settle by 12 Dec.
(UNHEX('71e17ea545d34b5ba993b52fb3a3138a'), 0, 'ADV-2026-2027-9F72BFA7', @emp, @mgr,
    '[SEED] Hyderabad client workshop', 'Client workshop - travel, stay and meals', 'SEED-1124 K',
    6000.0000, 6000.0000, 6000.0000, 6000.0000, 6000.0000, 6000.0000, 0.0000, 0.0000, @currency, @cc,
    'IN_PROGRESS', 'COMPLETE_NEW', 5000.0000, 6000.0000, 'Separate trip; earlier advances cover other visits.',
    '2026-10-26', '2026-12-12', '2026-10-27', '2026-10-31',
    'FULL_ADVANCE', 6000.0000, 'Approved in full for client workshop.', @mgr, '2026-10-23 11:00:00',
    '2026-10-25 12:00:00', 'CASH', '2026-10-25', NULL, NULL, NULL, 'Sindhu Yanala',
    'Seed: cash handed over', b'0', '2026-10-22 10:00:00', '2026-10-25 12:00:00'),

-- L: Kochi vendor audit, 9000 INR, UPI. Trip 2-6 Nov. Settle by 18 Dec.
(UNHEX('723b46ee28ab4b4b921e378c7a9af570'), 0, 'ADV-2026-2027-804C664A', @emp, @mgr,
    '[SEED] Kochi vendor audit', 'Vendor audit - flights, stay and local conveyance', 'SEED-1124 L',
    9000.0000, 9000.0000, 9000.0000, 9000.0000, 9000.0000, 9000.0000, 0.0000, 0.0000, @currency, @cc,
    'IN_PROGRESS', 'COMPLETE_NEW', 11000.0000, 9000.0000, 'Separate trip; earlier advances cover other visits.',
    '2026-11-01', '2026-12-18', '2026-11-02', '2026-11-06',
    'FULL_ADVANCE', 9000.0000, 'Approved in full for vendor audit.', @mgr, '2026-10-29 11:00:00',
    '2026-10-31 12:00:00', 'UPI', '2026-10-31', 'SEEDUPI20261031', NULL, 'sindhu.seed@upi', NULL,
    'Seed: UPI payment', b'0', '2026-10-28 10:00:00', '2026-10-31 12:00:00'),

-- M: Ahmedabad partner meeting, 4500 INR, bank transfer. Trip 10-14 Nov. Settle by 22 Dec.
(UNHEX('bfe1fa4c2f7d40e7865912e302cad2b2'), 0, 'ADV-2026-2027-CF595008', @emp, @mgr,
    '[SEED] Ahmedabad partner meeting', 'Partner meeting - travel and meals', 'SEED-1124 M',
    4500.0000, 4500.0000, 4500.0000, 4500.0000, 4500.0000, 4500.0000, 0.0000, 0.0000, @currency, @cc,
    'IN_PROGRESS', 'COMPLETE_NEW', 20000.0000, 4500.0000, 'Separate trip; earlier advances cover other visits.',
    '2026-11-09', '2026-12-22', '2026-11-10', '2026-11-14',
    'FULL_ADVANCE', 4500.0000, 'Approved in full for partner meeting.', @mgr, '2026-11-05 11:00:00',
    '2026-11-07 12:00:00', 'BANK_TRANSFER', '2026-11-07', 'SEEDNEFT20261107', 'HDFC Bank - A/c XXXX1234', NULL, NULL,
    'Seed: bank transfer', b'0', '2026-11-04 10:00:00', '2026-11-07 12:00:00'),

-- N: Kolkata deployment support, 15000 INR, bank transfer. Trip 16-20 Nov. Settle by 28 Dec.
(UNHEX('993ca0e313b644578913f32bcbe59780'), 0, 'ADV-2026-2027-64A61C0B', @emp, @mgr,
    '[SEED] Kolkata deployment support', 'Onsite deployment - flights, stay and meals', 'SEED-1124 N',
    15000.0000, 15000.0000, 15000.0000, 15000.0000, 15000.0000, 15000.0000, 0.0000, 0.0000, @currency, @cc,
    'IN_PROGRESS', 'COMPLETE_NEW', 24500.0000, 15000.0000, 'Separate trip; earlier advances cover other visits.',
    '2026-11-15', '2026-12-28', '2026-11-16', '2026-11-20',
    'FULL_ADVANCE', 15000.0000, 'Approved in full for deployment support.', @mgr, '2026-11-11 11:00:00',
    '2026-11-13 12:00:00', 'BANK_TRANSFER', '2026-11-13', 'SEEDNEFT20261113', 'HDFC Bank - A/c XXXX1234', NULL, NULL,
    'Seed: bank transfer', b'0', '2026-11-10 10:00:00', '2026-11-13 12:00:00'),

-- O: Jaipur training session, 7000 INR, cash. Trip 18-22 Nov. Settle by 5 Jan 2027.
(UNHEX('e11796d6bfb640c5bd449010ef2785d5'), 0, 'ADV-2026-2027-F67A14BD', @emp, @mgr,
    '[SEED] Jaipur training session', 'Training session - travel, stay and meals', 'SEED-1124 O',
    7000.0000, 7000.0000, 7000.0000, 7000.0000, 7000.0000, 7000.0000, 0.0000, 0.0000, @currency, @cc,
    'IN_PROGRESS', 'COMPLETE_NEW', 39500.0000, 7000.0000, 'Separate trip; earlier advances cover other visits.',
    '2026-11-17', '2027-01-05', '2026-11-18', '2026-11-22',
    'FULL_ADVANCE', 7000.0000, 'Approved in full for training session.', @mgr, '2026-11-13 11:00:00',
    '2026-11-15 12:00:00', 'CASH', '2026-11-15', NULL, NULL, NULL, 'Sindhu Yanala',
    'Seed: cash handed over', b'0', '2026-11-12 10:00:00', '2026-11-15 12:00:00');

-- Approval trail: created, submitted, manager approval, Finance approval, disbursement.
INSERT INTO audit_log (audit_id, action, entity_id, entity_name, old_value, new_value, performed_at, performed_by, reason, source)
SELECT UNHEX(REPLACE(UUID(), '-', '')), e.action, e.advance_id, 'CashAdvance', e.old_value, e.new_value,
       e.performed_at, e.performed_by, e.reason, 'SEED'
FROM (
    SELECT UNHEX('71e17ea545d34b5ba993b52fb3a3138a') advance_id, 'SEED-1124 K' reason, '2026-10-22' d1, '2026-10-23' d2, '2026-10-24' d3, '2026-10-25' d4
    UNION ALL SELECT UNHEX('723b46ee28ab4b4b921e378c7a9af570'), 'SEED-1124 L', '2026-10-28', '2026-10-29', '2026-10-30', '2026-10-31'
    UNION ALL SELECT UNHEX('bfe1fa4c2f7d40e7865912e302cad2b2'), 'SEED-1124 M', '2026-11-04', '2026-11-05', '2026-11-06', '2026-11-07'
    UNION ALL SELECT UNHEX('993ca0e313b644578913f32bcbe59780'), 'SEED-1124 N', '2026-11-10', '2026-11-11', '2026-11-12', '2026-11-13'
    UNION ALL SELECT UNHEX('e11796d6bfb640c5bd449010ef2785d5'), 'SEED-1124 O', '2026-11-12', '2026-11-13', '2026-11-14', '2026-11-15'
) a
JOIN LATERAL (
    SELECT a.advance_id, a.reason, 'CASH_ADVANCE_CREATED' action, NULL old_value, 'DRAFT' new_value, TIMESTAMP(a.d1, '10:00:00') performed_at, @emp performed_by
    UNION ALL SELECT a.advance_id, a.reason, 'CASH_ADVANCE_SUBMITTED', 'DRAFT', 'SUBMITTED', TIMESTAMP(a.d1, '10:30:00'), @emp
    UNION ALL SELECT a.advance_id, a.reason, 'CASH_ADVANCE_APPROVED', 'SUBMITTED', 'PENDING_FINANCE_APPROVAL', TIMESTAMP(a.d2, '11:00:00'), @mgr
    UNION ALL SELECT a.advance_id, a.reason, 'CASH_ADVANCE_APPROVED', 'PENDING_FINANCE_APPROVAL', 'APPROVED', TIMESTAMP(a.d3, '11:00:00'), @finance
    UNION ALL SELECT a.advance_id, a.reason, 'CASH_ADVANCE_DISBURSED', 'APPROVED', 'IN_PROGRESS', TIMESTAMP(a.d4, '12:00:00'), @finance
) e ON TRUE;

COMMIT;

-- Check:
-- SELECT advance_number, title, status, amount, expected_trip_start_date, expected_trip_end_date, settlement_due_date
-- FROM cash_advance WHERE notes LIKE 'SEED-1124 %' ORDER BY created_at;
