package com.expense_management_service.testsupport;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Decides which database every test context uses, and refuses the shared cloud database.
 *
 * <p>Rules:
 * <ul>
 *   <li>Default: an isolated in-memory H2 database, recreated for each context, never persisted.</li>
 *   <li>Opt-in: only when {@code XMS_TEST_DATASOURCE_URL} is set, and only to a localhost MySQL database. The
 *       username and password come from {@code XMS_TEST_DATASOURCE_USER} and {@code XMS_TEST_DATASOURCE_PASS}.</li>
 *   <li>Never {@code .env}: the {@code DB_URL} value there is ignored, because the datasource settings below always
 *       take precedence over it.</li>
 *   <li>Hard refusal: any URL that names a protected host fails before a connection is made.</li>
 * </ul>
 * Schema updates are disabled in every test context: H2 is created and dropped per context, and an explicit database
 * is only validated, never changed.
 */
public final class TestDatasourceIsolation {

    /** Hosts that must never be used by a test. The shared cloud database is named here. */
    public static final String[] PROTECTED_HOST_MARKERS = {
            "aivencloud.com",
            "mysql-2652f645-expensemanagement"
    };

    /** The default: a private in-memory database, created and dropped with the test context. */
    public static final String ISOLATED_H2_URL = "jdbc:h2:mem:xms_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;NON_KEYWORDS=VALUE";

    /** Name of the lowest-precedence source that fills placeholder gaps. */
    public static final String PLACEHOLDER_SOURCE_NAME = "xmsTestPlaceholders";

    /** Name of the property source that carries these settings. */
    public static final String PROPERTY_SOURCE_NAME = "xmsTestDatasourceIsolation";

    private static final Pattern LOCAL_MYSQL =
            Pattern.compile("jdbc:mysql://(localhost|127\\.0\\.0\\.1):\\d+/[A-Za-z0-9_]+(\\?.*)?");

    /** Test-only values for placeholders that have no default in the main configuration. */
    static final Map<String, String> TEST_PLACEHOLDERS = Map.ofEntries(
            Map.entry("APP_BASE_URL", "http://127.0.0.1:9"),
            Map.entry("AP_MAIL_ADDRESS", "ap-test@test.invalid"),
            Map.entry("FINANCE_MAIL_ADDRESS", "finance-test@test.invalid"),
            Map.entry("MAIL_FROM", "test@test.invalid"),
            Map.entry("MAIL_SMTP_HOST", "127.0.0.1"),
            Map.entry("MAIL_SMTP_USERNAME", "test"),
            Map.entry("MAIL_SMTP_PASSWORD", "test"),
            Map.entry("AWS_ACCESS_KEY_ID", "test-access-key"),
            Map.entry("AWS_SECRET_ACCESS_KEY", "test-secret-key"),
            Map.entry("AWS_REGION", "ap-south-1"),
            Map.entry("AWS_S3_BUCKET", "test-bucket"),
            Map.entry("EMPLOYEE_ONBOARDING_BASE_URL", "http://127.0.0.1:9"),
            Map.entry("EXCHANGE_RATE_API_URL", "http://127.0.0.1:9"),
            Map.entry("EXCHANGE_RATE_API_KEY", "test-key"),
            Map.entry("EXCHANGE_RATE_BASE_CURRENCY", "INR"),
            Map.entry("EXCHANGE_RATE_REFRESH_CRON", "0 0 0 31 2 *"),
            Map.entry("EXPENSE_BUSINESS_TIME_ZONE", "Asia/Kolkata"),
            Map.entry("PAYROLL_DEDUCTION_COMPONENT", "TEST_DEDUCTION"),
            Map.entry("PAYROLL_REPORT_EMAIL", "payroll-test@test.invalid"),
            Map.entry("RMS_BASE_URL", "http://127.0.0.1:9"),
            Map.entry("UMS_BASE_URL", "http://127.0.0.1:9"),
            Map.entry("UMS_ISSUER_URI", "https://test-issuer.invalid"),
            Map.entry("UMS_JWKS_URI", "http://127.0.0.1:9/jwks"));

    private TestDatasourceIsolation() {
    }

    /** Throws if the URL names a protected host. Checked before any connection is attempted. */
    public static void rejectProtectedHost(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        for (String marker : PROTECTED_HOST_MARKERS) {
            if (lower.contains(marker)) {
                throw new IllegalStateException("Test refused: the URL names a protected database host (" + marker + "). "
                        + "Tests never connect to the shared database.");
            }
        }
    }

    /** The datasource settings for this context. Never reads {@code DB_URL} or any other {@code .env} value. */
    public static Map<String, Object> settings(ConfigurableEnvironment environment) {
        String explicit = environment.getProperty("XMS_TEST_DATASOURCE_URL");
        Map<String, Object> props = new LinkedHashMap<>();
        if (explicit != null && !explicit.isBlank()) {
            rejectProtectedHost(explicit);
            if (!LOCAL_MYSQL.matcher(explicit).matches()) {
                throw new IllegalStateException("Test refused: XMS_TEST_DATASOURCE_URL must be a localhost MySQL URL "
                        + "(jdbc:mysql://localhost:PORT/DATABASE).");
            }
            props.put("spring.datasource.url", explicit);
            props.put("spring.datasource.username", environment.getProperty("XMS_TEST_DATASOURCE_USER", ""));
            props.put("spring.datasource.password", environment.getProperty("XMS_TEST_DATASOURCE_PASS", ""));
            props.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
            props.put("spring.jpa.hibernate.ddl-auto", "validate");
        } else {
            props.put("spring.datasource.url", ISOLATED_H2_URL);
            props.put("spring.datasource.username", "sa");
            props.put("spring.datasource.password", "");
            props.put("spring.datasource.driver-class-name", "org.h2.Driver");
            props.put("spring.jpa.hibernate.ddl-auto", "create-drop");
        }
        props.put("spring.flyway.enabled", "false");
        // The DB_* names used by the main configuration get the same isolated values, so no path can reach .env.
        props.put("DB_URL", props.get("spring.datasource.url"));
        props.put("DB_USERNAME", props.get("spring.datasource.username"));
        props.put("DB_PASSWORD", props.get("spring.datasource.password"));
        // Fake, non-routable values for every other external setting that the main configuration requires. They
        // used to come from .env. Nothing is called in tests: the discard port refuses connections, and the crons
        // never fire.
        return props;
    }

    /**
     * Datasource settings take the highest precedence, so no earlier source (including {@code .env}) can override them.
     * The placeholder values for the other external settings are added LAST: they only fill a gap, and never override a
     * value a test or a real environment supplies (for example a test's own JWT issuer).
     */
    public static void apply(ConfigurableEnvironment environment) {
        Map<String, Object> props = settings(environment);
        environment.getPropertySources().remove(PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, props));
        environment.getPropertySources().remove(PLACEHOLDER_SOURCE_NAME);
        environment.getPropertySources().addLast(new MapPropertySource(PLACEHOLDER_SOURCE_NAME,
                new LinkedHashMap<String, Object>(TEST_PLACEHOLDERS)));
        rejectProtectedHost(environment.getProperty("spring.datasource.url", ""));
    }
}
