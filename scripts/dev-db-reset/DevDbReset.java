import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Empties XMS transactional data (reports, approvals, advances, notifications, ...) and keeps admin/master data
 * (categories, policies, currencies, GL accounts, cost centers + budgets, approval flows, ...).
 * DEV ONLY - the dev DB is shared with the team.
 *
 * Run from the repo root (needs the MySQL driver from ~/.m2 and the DB_URL / DB_USERNAME / DB_PASSWORD in .env):
 *
 *   java -cp ~/.m2/repository/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar scripts/dev-db-reset/DevDbReset.java .env count
 *   java -cp <same jar> scripts/dev-db-reset/DevDbReset.java .env reset
 *   java -cp <same jar> scripts/dev-db-reset/DevDbReset.java .env restore <backup.sql>
 *
 *   count    read-only: rows that would be removed, per table.
 *   reset    count -> backup to scripts/dev-db-reset/backups/ -> asks you to type the schema name -> deletes in one
 *            transaction (rolled back unless every table ends up empty) -> resets cost_center_budget balances.
 *   restore  replays a backup written by reset.
 *
 * Every table must be listed in CLEAR or KEEP. If a new table shows up (a new feature/migration), the script stops
 * and names it - add it to one of the two lists, then run again. Never prints credentials.
 */
public class DevDbReset {

    /** Transactional data - emptied. */
    static final List<String> CLEAR = List.of(
            // expense reports and everything hanging off them
            "expense_line_tax_component", "expense_split", "cost_allocation", "receipt_ocr", "receipt",
            "policy_violation", "expense_line_item", "expense_report",
            // approval runtime
            "approval_assignment", "approval_line_item_review", "approval_split_review", "approval_level_instance",
            "approval_task",
            // finance verification
            "verification_query", "finance_verification_review",
            // budget consumption (the budgets themselves are kept, balances reset)
            "budget_encumbrance",
            // cash advances
            "salary_deduction_request", "cash_advance_recovery_case", "cash_advance_reconciliation_line",
            "cash_advance_reconciliation_run", "cash_advance_carry_forward",
            "cash_advance_adjustment", "cash_advance_repayment", "cash_advance_fee", "cash_advance",
            // invoice handoff / integration
            "invoice_sync", "integration_sync",
            // notifications, email, logs, search
            "notification_read", "notification", "outbound_email", "audit_log", "search_index", "cdc_failure_log");

    /** Admin/master data - never touched (cost_center_budget only has its running balances reset). */
    static final List<String> KEEP = List.of(
            "currency", "exchange_rate",
            "expense_category", "expense_category_tax_mapping", "tax_code", "tax_code_component", "gl_account",
            "cost_center", "cost_center_budget",
            "policy", "policy_version", "policy_rule", "policy_rule_limit", "policy_severity_threshold",
            "policy_group", "policy_group_member", "policy_assignment",
            "approval_flow", "approval_flow_criterion", "approval_level", "approval_level_approver", "approval_matrix",
            "department_approver", "finance_team_approver", "approval_delegation",
            "system_configuration", "saved_filter", "employee_cache", "project_cache", "flyway_schema_history");

    static final String BUDGET_RESET = "UPDATE cost_center_budget SET available_budget = budget_amount + COALESCE(rollover_from_previous, 0), "
            + "cash_advance_allocated = 0, cash_advance_fee_allocated = 0";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: DevDbReset.java <path-to-.env> <count|reset|restore> [backup.sql]");
            System.exit(2);
        }
        Map<String, String> env = readEnv(Path.of(args[0]));
        String mode = args[1];
        try (Connection c = DriverManager.getConnection(env.get("DB_URL"), env.get("DB_USERNAME"), env.get("DB_PASSWORD"))) {
            String schema = c.getCatalog();
            System.out.println("Database: " + schema + " @ " + host(env.get("DB_URL")));
            switch (mode) {
                case "count" -> {
                    if (checkTables(c)) printCounts(c);
                }
                case "reset" -> reset(c, schema);
                case "restore" -> {
                    if (args.length < 3) throw new IllegalArgumentException("restore needs the backup file");
                    restore(c, Path.of(args[2]), schema);
                }
                default -> throw new IllegalArgumentException("mode must be count, reset or restore");
            }
        }
    }

    /** False (and prints why) when a table in the DB is in neither list. */
    static boolean checkTables(Connection c) throws SQLException {
        Set<String> unknown = new TreeSet<>(tables(c));
        unknown.removeAll(CLEAR);
        unknown.removeAll(KEEP);
        if (unknown.isEmpty()) return true;
        System.out.println("\nSTOPPED - these tables are in neither CLEAR nor KEEP:");
        unknown.forEach(t -> System.out.println("  " + t));
        System.out.println("Add each one to CLEAR (transactional) or KEEP (master data) in DevDbReset.java, then run again.");
        return false;
    }

    static List<String> clearable(Connection c) throws SQLException {
        Set<String> existing = tables(c);
        return CLEAR.stream().filter(existing::contains).toList();
    }

    static long printCounts(Connection c) throws SQLException {
        long total = 0;
        for (String t : clearable(c)) {
            long n = count(c, t);
            total += n;
            System.out.printf("  %-36s %d%n", t, n);
        }
        System.out.println("TOTAL rows to remove: " + total);
        return total;
    }

    static void reset(Connection c, String schema) throws Exception {
        if (!checkTables(c)) return;
        System.out.println("\nRows that will be removed:");
        long total = printCounts(c);

        Path dir = Path.of("scripts", "dev-db-reset", "backups");
        Files.createDirectories(dir);
        Path backup = dir.resolve("xms_backup_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")) + ".sql");
        backup(c, backup);

        System.out.print("\nThis deletes " + total + " rows from '" + schema + "' (shared with the team). Type the schema name to continue: ");
        String answer = new BufferedReader(new InputStreamReader(System.in)).readLine();
        if (answer == null || !answer.trim().equals(schema)) {
            System.out.println("Cancelled - nothing deleted. (Backup kept at " + backup + ")");
            return;
        }

        List<String> tables = clearable(c);
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            s.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String t : tables) {
                System.out.printf("  deleted %-36s %d%n", t, s.executeUpdate("DELETE FROM `" + t + "`"));
            }
            System.out.println("  reset cost_center_budget balances: " + s.executeUpdate(BUDGET_RESET) + " row(s)");
            s.execute("SET FOREIGN_KEY_CHECKS = 1");

            long remaining = 0;
            for (String t : tables) remaining += count(c, t);
            if (remaining != 0) {
                c.rollback();
                System.out.println("ROLLED BACK - " + remaining + " rows reappeared (is the backend writing?). Nothing changed.");
                return;
            }
            c.commit();
            System.out.println("DONE - " + tables.size() + " tables emptied, budgets reset. Backup: " + backup.toAbsolutePath());
            System.out.println("Restart the backend and refresh the browser. Receipt files in S3 are not deleted.");
        } catch (SQLException e) {
            c.rollback();
            System.out.println("ROLLED BACK on error - nothing changed: " + e.getMessage());
            throw e;
        }
    }

    /** Every row of the cleared tables (INSERT) plus cost_center_budget (REPLACE, to put balances back). */
    static void backup(Connection c, Path file) throws Exception {
        List<String> tables = new ArrayList<>(clearable(c));
        tables.add("cost_center_budget");
        long rows = 0;
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("-- XMS backup before transactional reset - restore with: DevDbReset.java .env restore <this file>\n");
            w.write("SET FOREIGN_KEY_CHECKS = 0;\n");
            for (String t : tables) {
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM `" + t + "`")) {
                    ResultSetMetaData md = rs.getMetaData();
                    int cols = md.getColumnCount();
                    StringBuilder colList = new StringBuilder();
                    for (int i = 1; i <= cols; i++) colList.append(i > 1 ? "," : "").append('`').append(md.getColumnName(i)).append('`');
                    String verb = t.equals("cost_center_budget") ? "REPLACE" : "INSERT";
                    while (rs.next()) {
                        StringBuilder sb = new StringBuilder(verb).append(" INTO `").append(t).append("` (").append(colList).append(") VALUES (");
                        for (int i = 1; i <= cols; i++) sb.append(i > 1 ? "," : "").append(literal(rs, i, md.getColumnType(i)));
                        w.write(sb.append(");\n").toString());
                        rows++;
                    }
                }
            }
            w.write("SET FOREIGN_KEY_CHECKS = 1;\n");
        }
        System.out.println("\nBackup written (" + rows + " rows): " + file.toAbsolutePath());
    }

    static void restore(Connection c, Path file, String schema) throws Exception {
        List<String> statements = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("--")).toList();
        System.out.print("Replay " + statements.size() + " statements from " + file.getFileName() + " into '" + schema
                + "'? Type the schema name to continue: ");
        String answer = new BufferedReader(new InputStreamReader(System.in)).readLine();
        if (answer == null || !answer.trim().equals(schema)) {
            System.out.println("Cancelled - nothing restored.");
            return;
        }
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            for (String sql : statements) s.execute(sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql);
            c.commit();
            System.out.println("RESTORED " + statements.size() + " statements.");
        } catch (SQLException e) {
            c.rollback();
            System.out.println("ROLLED BACK on error - nothing restored: " + e.getMessage());
            throw e;
        }
    }

    static Set<String> tables(Connection c) throws SQLException {
        Set<String> out = new TreeSet<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    static long count(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static String literal(ResultSet rs, int i, int type) throws SQLException {
        if (type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY || type == Types.BLOB) {
            byte[] b = rs.getBytes(i);
            if (b == null) return "NULL";
            StringBuilder hex = new StringBuilder("X'");
            for (byte x : b) hex.append(String.format("%02x", x));
            return hex.append('\'').toString();
        }
        String v = rs.getString(i);
        if (v == null) return "NULL";
        if (type == Types.BIT || type == Types.BOOLEAN) return rs.getBoolean(i) ? "1" : "0";
        return "'" + v.replace("\\", "\\\\").replace("'", "''").replace("\n", "\\n").replace("\r", "\\r") + "'";
    }

    /** host[:port] from a jdbc:mysql://host:port/db?... URL - no credentials. */
    static String host(String url) {
        if (url == null) return "?";
        String rest = url.replaceFirst("^jdbc:mysql://", "");
        int slash = rest.indexOf('/');
        return slash >= 0 ? rest.substring(0, slash) : rest;
    }

    static Map<String, String> readEnv(Path p) throws IOException {
        Map<String, String> m = new HashMap<>();
        for (String line : Files.readAllLines(p)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
            int i = line.indexOf('=');
            String v = line.substring(i + 1).trim();
            if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
                v = v.substring(1, v.length() - 1);
            }
            m.put(line.substring(0, i).trim(), v);
        }
        return m;
    }
}
