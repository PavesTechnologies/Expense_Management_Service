-- DEV ONLY. Seeds five cash advances that are fully approved (manager -> Finance) and disbursed, with the trips already
-- over, so after-trip expense reports can be created against them on 2026-11-20. Do not run against production.
--
-- State of every advance: IN_PROGRESS (the app's approved-and-disbursed state, linkable to an expense report).
--   * Trip dates are in the past. Every trip ends on or after 2026-10-30, so the 30-day expense window
--     (expected_trip_end_date + 30 days, see CashAdvanceExpenseWindow) is still open and the advance is not OVERDUE.
--   * settlement_due_date is in the future (Dec 2026).
--   * Approval trail: CREATED -> SUBMITTED -> manager APPROVED (-> PENDING_FINANCE_APPROVAL) -> Finance APPROVED
--     -> DISBURSED, in audit_log; the manager decision (FULL_ADVANCE) is stored on the advance.
--   * Cost center budget is NOT charged (budget_fiscal_year stays NULL, so settlement skips budget movements).
--
-- Employee: Sindhu Yanala 5100014, manager Rama Durgam 5100023 (also owner of cost center Eng, so there is no separate
-- cost center approval stage). Change the variables below to seed for another employee.
-- Rollback: after-trip-cash-advances-rollback.sql.

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
-- F: Bengaluru client visit, 5000 INR, cash. Trip 26-30 Oct. Window open until 29 Nov.
(UNHEX('fcc23f1bbd9c4af6927b63395cfcc008'), 0, 'ADV-2026-2027-E88F0AEB', @emp, @mgr,
    '[SEED] Bengaluru client visit', 'Client onsite visit - travel, stay and local conveyance', 'SEED-AFTERTRIP F',
    5000.0000, 5000.0000, 5000.0000, 5000.0000, 5000.0000, 5000.0000, 0.0000, 0.0000, @currency, @cc,
    'IN_PROGRESS', 'COMPLETE_NEW', 0.0000, 5000.0000, NULL,
    '2026-10-24', '2026-12-10', '2026-10-26', '2026-10-30',
    'FULL_ADVANCE', 5000.0000, 'Approved in full for client visit.', @mgr, '2026-10-21 11:00:00',
    '2026-10-23 12:00:00', 'CASH', '2026-10-23', NULL, NULL, NULL, 'Sindhu Yanala',
    'Seed: cash handed over', b'0', '2026-10-20 10:00:00', '2026-10-23 12:00:00'),

-- G: Chennai training workshop, 10000 INR, UPI. Trip 29 Oct - 2 Nov. Window open until 2 Dec.
(UNHEX('af37e9a9ddd54d2d998b8e32036e968a'), 0, 'ADV-2026-2027-7418BB1F', @emp, @mgr,
    '[SEED] Chennai training workshop', 'Training workshop - travel, stay and meals', 'SEED-AFTERTRIP G',
    10000.0000, 10000.0000, 10000.0000, 10000.0000, 10000.0000, 10000.0000, 0.0000, 0.0000, @currency, @cc,
    'IN_PROGRESS', 'COMPLETE_NEW', 5000.0000, 10000.0000, 'Separate trip; the Bengaluru advance is for a different visit.',
    '2026-10-28', '2026-12-15', '2026-10-29', '2026-11-02',
    'FULL_ADVANCE', 10000.0000, 'Approved in full for workshop.', @mgr, '2026-10-25 11:00:00',
    '2026-10-27 12:00:00', 'UPI', '2026-10-27', 'SEEDUPI20261027', NULL, 'sindhu.seed@upi', NULL,
    'Seed: UPI payment', b'0', '2026-10-24 10:00:00', '2026-10-27 12:00:00'),

-- H: Mumbai audit trip, 8000 INR, bank transfer. Trip 3-7 Nov. Window open until 7 Dec.
(UNHEX('36b2d34e45264b4782879d1aec104054'), 0, 'ADV-2026-2027-E68E90BB', @emp, @mgr,
    '[SEED] Mumbai audit trip', 'Client audit - travel, stay and meals', 'SEED-AFTERTRIP H',
    8000.0000, 8000.0000, 8000.0000, 8000.0000, 8000.0000, 8000.0000, 0.0000, 0.0000, @currency, @cc,
    'IN_PROGRESS', 'COMPLETE_NEW', 15000.0000, 8000.0000, 'Separate trip; earlier advances cover other visits.',
    '2026-11-01', '2026-12-20', '2026-11-03', '2026-11-07',
    'FULL_ADVANCE', 8000.0000, 'Approved in full for audit trip.', @mgr, '2026-10-29 11:00:00',
    '2026-10-31 12:00:00', 'BANK_TRANSFER', '2026-10-31', 'SEEDNEFT20261031', 'HDFC Bank - A/c XXXX1234', NULL, NULL,
    'Seed: bank transfer', b'0', '2026-10-28 10:00:00', '2026-10-31 12:00:00'),

-- I: Delhi conference, 12000 INR, bank transfer. Trip 9-13 Nov. Window open until 13 Dec.
(UNHEX('a6b24849109242f4aa680c0bf735362a'), 0, 'ADV-2026-2027-B7FDEA2E', @emp, @mgr,
    '[SEED] Delhi tech conference', 'Conference - flights, stay and registration', 'SEED-AFTERTRIP I',
    12000.0000, 12000.0000, 12000.0000, 12000.0000, 12000.0000, 12000.0000, 0.0000, 0.0000, @currency, @cc,
    'IN_PROGRESS', 'COMPLETE_NEW', 23000.0000, 12000.0000, 'Separate trip; earlier advances cover other visits.',
    '2026-11-06', '2026-12-25', '2026-11-09', '2026-11-13',
    'FULL_ADVANCE', 12000.0000, 'Approved in full for conference.', @mgr, '2026-11-03 11:00:00',
    '2026-11-05 12:00:00', 'BANK_TRANSFER', '2026-11-05', 'SEEDNEFT20261105', 'HDFC Bank - A/c XXXX1234', NULL, NULL,
    'Seed: bank transfer', b'0', '2026-11-02 10:00:00', '2026-11-05 12:00:00'),

-- J: Pune site visit, 3000 INR, cash. Trip 12-18 Nov. Window open until 18 Dec.
(UNHEX('dc635366409f45feb093077041ba7dfd'), 0, 'ADV-2026-2027-69EC4CE9', @emp, @mgr,
    '[SEED] Pune site visit', 'Site visit - local travel and meals', 'SEED-AFTERTRIP J',
    3000.0000, 3000.0000, 3000.0000, 3000.0000, 3000.0000, 3000.0000, 0.0000, 0.0000, @currency, @cc,
    'IN_PROGRESS', 'COMPLETE_NEW', 35000.0000, 3000.0000, 'Separate trip; earlier advances cover other visits.',
    '2026-11-10', '2026-12-31', '2026-11-12', '2026-11-18',
    'FULL_ADVANCE', 3000.0000, 'Approved in full for site visit.', @mgr, '2026-11-07 11:00:00',
    '2026-11-09 12:00:00', 'CASH', '2026-11-09', NULL, NULL, NULL, 'Sindhu Yanala',
    'Seed: cash handed over', b'0', '2026-11-06 10:00:00', '2026-11-09 12:00:00');

-- Approval trail, in the same form the app writes (old/new value = plain status).
-- For each advance: created, submitted, manager approval, Finance approval, disbursement.
INSERT INTO audit_log (audit_id, action, entity_id, entity_name, old_value, new_value, performed_at, performed_by, reason, source)
SELECT UNHEX(REPLACE(UUID(), '-', '')), e.action, e.advance_id, 'CashAdvance', e.old_value, e.new_value,
       e.performed_at, e.performed_by, e.reason, 'SEED'
FROM (
    SELECT UNHEX('fcc23f1bbd9c4af6927b63395cfcc008') advance_id, 'SEED-AFTERTRIP F' reason, '2026-10-20' d1, '2026-10-21' d2, '2026-10-22' d3, '2026-10-23' d4
    UNION ALL SELECT UNHEX('af37e9a9ddd54d2d998b8e32036e968a'), 'SEED-AFTERTRIP G', '2026-10-24', '2026-10-25', '2026-10-26', '2026-10-27'
    UNION ALL SELECT UNHEX('36b2d34e45264b4782879d1aec104054'), 'SEED-AFTERTRIP H', '2026-10-28', '2026-10-29', '2026-10-30', '2026-10-31'
    UNION ALL SELECT UNHEX('a6b24849109242f4aa680c0bf735362a'), 'SEED-AFTERTRIP I', '2026-11-02', '2026-11-03', '2026-11-04', '2026-11-05'
    UNION ALL SELECT UNHEX('dc635366409f45feb093077041ba7dfd'), 'SEED-AFTERTRIP J', '2026-11-06', '2026-11-07', '2026-11-08', '2026-11-09'
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
-- SELECT advance_number, title, status, amount, expected_trip_start_date, expected_trip_end_date, settlement_due_date,
--        DATE_ADD(expected_trip_end_date, INTERVAL 30 DAY) AS expense_window_ends
-- FROM cash_advance WHERE notes LIKE 'SEED-AFTERTRIP %' ORDER BY created_at;
