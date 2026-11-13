package com.expense_management_service.concurrency;

import com.expense_management_service.service.impl.CashAdvanceBalanceCalculator;
import com.expense_management_service.service.impl.CashAdvanceBalanceDriftService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The drift check against real MySQL: stored cache vs records, reversed offsets excluded, difference reported. */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=${CA_IT_MYSQL_URL:}",
        "spring.datasource.username=${CA_IT_MYSQL_USER:}",
        "spring.datasource.password=${CA_IT_MYSQL_PASSWORD:}"
})
@Import({CashAdvanceBalanceCalculator.class, CashAdvanceBalanceDriftService.class})
@EnabledIfEnvironmentVariable(named = "CA_IT_MYSQL_URL", matches = ".*ca_it.*")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CashAdvanceDriftMysqlTest {

    private static final String EMP = "CONC-DRIFT";

    @Autowired CashAdvanceBalanceDriftService driftService;
    @Autowired JdbcTemplate jdbc;

    private byte[] currencyId;
    private byte[] costCenterId;
    private byte[] reportId;

    @BeforeEach
    void setUp() {
        assertTrue(System.getenv("CA_IT_MYSQL_URL").contains("ca_it"), "Refusing to run outside a ca_it test database");
        currencyId = bytes(UUID.randomUUID());
        costCenterId = bytes(UUID.randomUUID());
        jdbc.update("INSERT INTO currency (currency_id, currency_code, currency_name, decimal_places) VALUES (?, ?, 'Test', 2)",
                currencyId, "T" + code());
        jdbc.update("INSERT INTO cost_center (cost_center_id, department_uuid, cost_center_code, cost_center_name, allow_unbudgeted, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'Test', 0, NOW(6), NOW(6))", costCenterId, bytes(UUID.randomUUID()), "CC" + code());
        reportId = bytes(UUID.randomUUID());
        jdbc.update("INSERT INTO expense_report (report_id, employee_id, report_number, title, fiscal_year, cost_center_id, currency_id, "
                + "invoice_handoff_status, company_paid, expense_deadline_reopened, created_at, updated_at, version, report_status, payment_routing_status, total_amount, reimbursable_amount) "
                + "VALUES (?, ?, ?, 'Drift', '2026', ?, ?, 'NOT_APPLICABLE', 0, 0, NOW(6), NOW(6), 0, 'APPROVED', 'NONE', 1000, 1000)",
                reportId, EMP, "DR" + code(), costCenterId, currencyId);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM cash_advance_adjustment WHERE advance_id IN (SELECT advance_id FROM cash_advance WHERE employee_id = ?)", EMP);
        jdbc.update("DELETE FROM cash_advance WHERE employee_id = ?", EMP);
        jdbc.update("DELETE FROM expense_report WHERE employee_id = ?", EMP);
        jdbc.update("DELETE FROM cost_center WHERE cost_center_id = ?", costCenterId);
        jdbc.update("DELETE FROM currency WHERE currency_id = ?", currencyId);
    }

    private UUID advance(String amount, String stored) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO cash_advance (advance_id, currency_id, cost_center_id, employee_id, amount, outstanding_balance, status, "
                        + "expense_deadline_reopened, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, ?, 'DISBURSED', 0, NOW(6), NOW(6), 0)",
                bytes(id), currencyId, costCenterId, EMP, new BigDecimal(amount), stored == null ? null : new BigDecimal(stored));
        return id;
    }

    private void offset(UUID advanceId, String amount, boolean reversed) {
        jdbc.update("INSERT INTO cash_advance_adjustment (adjustment_id, advance_id, report_id, adjusted_amount, adjusted_at, adjusted_by, version, reversed) "
                        + "VALUES (?, ?, ?, ?, NOW(6), 'FIN001', 0, ?)",
                bytes(UUID.randomUUID()), bytes(advanceId), reportId, new BigDecimal(amount), reversed);
    }

    @Test
    void driftIsReported_withTheDifference_andReversedOffsetsDoNotCount() {
        UUID inSync = advance("1000.00", "700.00");
        offset(inSync, "300.00", false);
        offset(inSync, "500.00", true);            // reversed: must not count, cache 700 is right
        UUID drifted = advance("1000.00", "900.00");
        offset(drifted, "300.00", false);          // records say 700; cache says 900

        List<CashAdvanceBalanceCalculator.Drift> found = driftService.findDrift().stream()
                .filter(d -> d.employeeId().equals(EMP)).toList();

        assertEquals(1, found.size(), "only the drifted advance is reported");
        CashAdvanceBalanceCalculator.Drift d = found.get(0);
        assertEquals(drifted, d.advanceId());
        assertEquals(0, new BigDecimal("900.00").compareTo(d.stored()));
        assertEquals(0, new BigDecimal("700.00").compareTo(d.computed()));
        assertEquals(0, new BigDecimal("200.00").compareTo(d.difference()));
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static String code() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
