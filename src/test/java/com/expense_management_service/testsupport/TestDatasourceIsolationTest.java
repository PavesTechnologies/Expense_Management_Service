package com.expense_management_service.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MapPropertySource;

/** The datasource guard: the shared cloud host is always refused, and the default is isolated in-memory H2. */
class TestDatasourceIsolationTest {

    private static StandardEnvironment environmentWith(String key, String value) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test-input", Map.of(key, value)));
        return env;
    }

    @Test
    void defaultIsTheIsolatedInMemoryDatabase() {
        Map<String, Object> settings = TestDatasourceIsolation.settings(new StandardEnvironment());
        assertThat(settings.get("spring.datasource.url")).isEqualTo(TestDatasourceIsolation.ISOLATED_H2_URL);
        assertThat(settings.get("spring.jpa.hibernate.ddl-auto")).isEqualTo("create-drop");
        assertThat(settings.get("DB_URL")).isEqualTo(TestDatasourceIsolation.ISOLATED_H2_URL);
        assertThat(settings.get("spring.flyway.enabled")).isEqualTo("false");
    }

    @Test
    void sharedCloudHostIsRefused_evenWhenExplicitlyConfigured() {
        String shared = "jdbc:mysql://mysql-2652f645-expensemanagement.d.aivencloud.com:18971/expense_management";
        assertThatThrownBy(() -> TestDatasourceIsolation.settings(environmentWith("XMS_TEST_DATASOURCE_URL", shared)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("protected database host");
    }

    @Test
    void anyAivenHostIsRefused() {
        assertThatThrownBy(() -> TestDatasourceIsolation.rejectProtectedHost("jdbc:mysql://other.aivencloud.com:1/db"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void explicitDatasourceMustBeLocalMysql() {
        assertThatThrownBy(() -> TestDatasourceIsolation.settings(
                environmentWith("XMS_TEST_DATASOURCE_URL", "jdbc:mysql://db.example.com:3306/xms_test")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("localhost MySQL");
    }

    @Test
    void explicitLocalDatasourceIsAcceptedAndValidatedOnly() {
        Map<String, Object> settings = TestDatasourceIsolation.settings(environmentWith(
                "XMS_TEST_DATASOURCE_URL", "jdbc:mysql://localhost:33306/xms_schema_check"));
        assertThat(settings.get("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    void nothingIsReadFromTheDotenvFile() {
        // The test JVM disables the .env loader (surefire systemPropertyVariables). Verify the switch is set.
        assertThat(System.getProperty("springdotenv.enabled")).isEqualTo("false");
    }
}
