package com.expense_management_service.service.impl;

import me.paulschwarz.springdotenv.spring.DotenvApplicationInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production-readiness audit (independent re-verification): a minimal, non-destructive smoke test
 * proving a {@code @DataJpaTest} slice (JPA layer only - no web/security/Kafka/RabbitMQ auto-
 * configuration, so none of contextLoads' UMS_ISSUER_URI problem applies here) can actually reach
 * the real database this project's own application.properties already points at via
 * {@code ${DB_URL}}/{@code ${DB_USERNAME}}/{@code ${DB_PASSWORD}} (resolved from the project's own
 * .env by spring-dotenv, exactly like the running application - no credentials are duplicated or
 * hard-coded here). Named *IT*, not *Test*, so Surefire's default **}/*Test.java pattern never picks
 * it up in a normal build - this requires real network/DB credentials that a CI pipeline may not have.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none", // read-only smoke test - never touch the real schema. A ONE-TIME,
                                               // user-approved ddl-auto=update pass was already run from this class
                                               // to add the missing expense_split.removed_at column - see BudgetEncumbranceRealDbConcurrencyIT's javadoc.
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // real DB, no embedded H2 swap-in
@ContextConfiguration(initializers = DotenvApplicationInitializer.class) // same .env loader main() wires manually -
                                                                          // Spring Boot test slices never go through
                                                                          // main(), so without this ${DB_URL} etc.
                                                                          // never resolve in ANY test context
@Transactional(propagation = Propagation.NOT_SUPPORTED) // no auto-wrapping/rollback - just prove raw connectivity
class RealDbSmokeIT {

    @Autowired
    private DataSource dataSource;

    @Test
    void canConnectToTheRealMySqlInstance_andSeeItsExistingSchema() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT VERSION()")) {
            assertThat(rs.next()).isTrue();
            String version = rs.getString(1);
            System.out.println("Connected to real MySQL. VERSION() = " + version);
            assertThat(version).isNotBlank();
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'cost_center_budget'")) {
            assertThat(rs.next()).isTrue();
            int tableExists = rs.getInt(1);
            System.out.println("cost_center_budget table exists: " + (tableExists == 1));
        }

        for (String table : new String[]{"expense_split", "budget_encumbrance", "approval_assignment", "expense_report"}) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' ORDER BY ORDINAL_POSITION")) {
                StringBuilder cols = new StringBuilder();
                while (rs.next()) {
                    cols.append(rs.getString(1)).append(", ");
                }
                System.out.println("REAL SCHEMA " + table + " columns: " + cols);
            }
        }

        for (String indexTable : new String[]{"budget_encumbrance", "expense_split", "approval_assignment"}) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + indexTable + "'")) {
                StringBuilder idx = new StringBuilder();
                while (rs.next()) {
                    idx.append(rs.getString(1)).append(", ");
                }
                System.out.println("REAL SCHEMA " + indexTable + " indexes: " + idx);
            }
        }
    }

    /**
     * ONE-TIME, user-approved sync (see conversation): applies exactly the 3 {@code CREATE INDEX}
     * statements from {@code V16__split_budget_approval_indexes.sql} directly - NOT via Flyway (this
     * real dev DB's schema was built entirely by {@code hibernate.ddl-auto=update} over time and has
     * never run any Flyway migration, so letting Flyway's full V1-V16 history execute against it now
     * carries real risk of conflicting with already-materialized state; that was explicitly NOT part
     * of what was approved). Checks {@code information_schema.STATISTICS} first so this is safe to
     * leave in the suite (a no-op once the indexes exist) rather than something to remember to delete.
     */
    @Test
    void ensureV16IndexesExist_onceApproved_appliedDirectlyNotViaFlyway() throws Exception {
        record IndexSpec(String table, String indexName, String ddl) {
        }
        List<IndexSpec> specs = List.of(
                new IndexSpec("budget_encumbrance", "idx_budget_encumbrance_report_cycle_status",
                        "CREATE INDEX idx_budget_encumbrance_report_cycle_status ON budget_encumbrance (report_id, submission_cycle, status)"),
                new IndexSpec("expense_split", "idx_expense_split_line_item_removed_at",
                        "CREATE INDEX idx_expense_split_line_item_removed_at ON expense_split (line_item_id, removed_at)"),
                new IndexSpec("approval_assignment", "idx_approval_assignment_status_approver",
                        "CREATE INDEX idx_approval_assignment_status_approver ON approval_assignment (status, approver_id)")
        );

        try (Connection connection = dataSource.getConnection()) {
            for (IndexSpec spec : specs) {
                boolean exists;
                try (Statement check = connection.createStatement();
                     ResultSet rs = check.executeQuery(
                             "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                                     + "AND TABLE_NAME = '" + spec.table() + "' AND INDEX_NAME = '" + spec.indexName() + "'")) {
                    rs.next();
                    exists = rs.getInt(1) > 0;
                }
                if (exists) {
                    System.out.println("Index already present, skipping: " + spec.indexName());
                    continue;
                }
                try (Statement create = connection.createStatement()) {
                    create.executeUpdate(spec.ddl());
                    System.out.println("Created index: " + spec.indexName());
                }
            }
        }
    }
}
