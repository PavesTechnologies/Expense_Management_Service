-- Guard: a cash advance may only be CLOSED with an outstanding balance of zero, at its currency's
-- decimal precision. Enforced by triggers on cash_advance.
--
-- 1. Precondition. If any existing CLOSED advance has a non-zero (or NULL) balance, this migration
--    stops with an error and changes NOTHING. Such records must be investigated and resolved by hand;
--    they are never silently rewritten here.
-- 2. Triggers reject any INSERT or UPDATE that would set CLOSED on a non-zero balance.
--
-- Applies to an existing database, where the cash_advance and currency tables already exist. On a fresh
-- database, Flyway runs before Hibernate creates the tables, so this migration will fail there; run it
-- after the first startup instead (the same ordering caveat as V30).

DROP PROCEDURE IF EXISTS assert_closed_advances_have_zero_balance;

DELIMITER //
CREATE PROCEDURE assert_closed_advances_have_zero_balance()
BEGIN
  DECLARE bad INT DEFAULT 0;

  SELECT COUNT(*) INTO bad
  FROM cash_advance a
  LEFT JOIN currency c ON c.currency_id = a.currency_id
  WHERE a.status = 'CLOSED'
    AND (a.outstanding_balance IS NULL
         OR ROUND(a.outstanding_balance, COALESCE(c.decimal_places, 2)) <> 0);

  IF bad > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V33 aborted: CLOSED cash advances with a non-zero balance exist. No rows were changed. Resolve them manually, then re-run.';
  END IF;
END//
DELIMITER ;

CALL assert_closed_advances_have_zero_balance();
DROP PROCEDURE assert_closed_advances_have_zero_balance;

DELIMITER //
CREATE TRIGGER trg_cash_advance_closed_zero_insert
BEFORE INSERT ON cash_advance
FOR EACH ROW
BEGIN
  DECLARE dp INT DEFAULT 2;
  IF NEW.status = 'CLOSED' THEN
    SELECT COALESCE(MAX(decimal_places), 2) INTO dp FROM currency WHERE currency_id = NEW.currency_id;
    IF NEW.outstanding_balance IS NULL OR ROUND(NEW.outstanding_balance, dp) <> 0 THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'Cash advance cannot be CLOSED while a balance is outstanding';
    END IF;
  END IF;
END//

CREATE TRIGGER trg_cash_advance_closed_zero_update
BEFORE UPDATE ON cash_advance
FOR EACH ROW
BEGIN
  DECLARE dp INT DEFAULT 2;
  IF NEW.status = 'CLOSED' THEN
    SELECT COALESCE(MAX(decimal_places), 2) INTO dp FROM currency WHERE currency_id = NEW.currency_id;
    IF NEW.outstanding_balance IS NULL OR ROUND(NEW.outstanding_balance, dp) <> 0 THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'Cash advance cannot be CLOSED while a balance is outstanding';
    END IF;
  END IF;
END//
DELIMITER ;
