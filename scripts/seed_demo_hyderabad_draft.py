"""Development-only seed: one historical DRAFT cash advance request (INR 1,000) for a demo employee.

Inserts a single cash_advance row in DRAFT status with October 2026 dates. It writes no approval, disbursement,
expense report, settlement or repayment rows, and it does not change any existing record. It is idempotent: if the
demo request already exists it does nothing.

The application's create-time date checks (needed-by and settlement dates must be after today) are NOT bypassed
for the live API. This script writes the historical DRAFT directly, the same way the other seed scripts do.
"""
import re
import uuid
from urllib.parse import urlparse

import pymysql

ENV_FILE = r"C:\Users\Lokeshwari.Busam\Desktop\Myfiles\projects\Expense_Management_Service\.env"

EMPLOYEE_ID = "5100021"          # Kandukoori Niharika, verified in employee_cache
MANAGER_ID = "5100023"           # Ramagopal Durgam, her reporting manager (employee_cache)
COST_CENTER_ID = "071e031b-a9e8-4b7a-b23e-d53b7bdea90e"  # Engineering
CURRENCY_ID = "957eac81-b81d-4d2c-8d93-6bb3c979dbf4"     # INR

TITLE = "[OCT-DEMO] Hyderabad client visit cash advance"
PURPOSE = ("Official business travel to Hyderabad for client meetings, project discussions, requirement gathering, "
           "and business-related activities.")
NOTES = ("Cash advance required for meals, local transportation, and other incidental expenses during the official "
         "business trip.")


def load_env():
    env = {}
    with open(ENV_FILE, encoding="utf-8") as fh:
        for line in fh:
            m = re.match(r"^\s*([A-Z_]+)\s*=\s*(.*?)\s*$", line)
            if m:
                env[m.group(1)] = m.group(2)
    return env


def b(u):
    return f"UNHEX(REPLACE('{u}','-',''))"


def main():
    env = load_env()
    p = urlparse(env["DB_URL"].replace("jdbc:", "", 1))
    conn = pymysql.connect(host=p.hostname, port=p.port, user=env["DB_USERNAME"], password=env["DB_PASSWORD"],
                           database=p.path.lstrip("/"), ssl={"ssl": True}, charset="utf8mb4", autocommit=False)
    advance_id = str(uuid.uuid4())
    try:
        with conn.cursor() as c:
            c.execute("SELECT COUNT(*) FROM cash_advance WHERE employee_id=%s AND title=%s", (EMPLOYEE_ID, TITLE))
            if c.fetchone()[0]:
                print("demo request already exists; nothing inserted")
                conn.rollback()
                return

            c.execute(f"""INSERT INTO cash_advance (advance_id, version, employee_id, manager_id, cost_center_id, currency_id,
              title, purpose, notes, amount, base_amount, outstanding_balance, status,
              needed_by_date, settlement_due_date, expected_trip_start_date, expected_trip_end_date,
              expense_deadline_reopened, carried_in_amount, carried_out_amount, carry_forward_ready_amount,
              created_at, updated_at)
              VALUES ({b(advance_id)}, 0, %s, %s, {b(COST_CENTER_ID)}, {b(CURRENCY_ID)},
              %s, %s, %s, 1000, 1000, 0, 'DRAFT',
              '2026-10-20', '2026-11-10', '2026-10-20', '2026-11-02',
              0, 0, 0, 0,
              '2026-10-19 09:00:00', '2026-10-19 09:00:00')""",
              (EMPLOYEE_ID, MANAGER_ID, TITLE, PURPOSE, NOTES))
            print("cash_advance inserted:", c.rowcount)

            c.execute("SELECT HEX(advance_id), status, amount, created_at, needed_by_date, expected_trip_start_date, "
                      "expected_trip_end_date, settlement_due_date FROM cash_advance WHERE HEX(advance_id)=%s",
                      (advance_id.replace("-", "").upper(),))
            print("verify:", c.fetchone())
        conn.commit()
        print("COMMITTED")
    except Exception as exc:
        conn.rollback()
        print("ROLLED BACK:", exc)
    finally:
        conn.close()


if __name__ == "__main__":
    main()
