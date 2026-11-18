package com.expense_management_service.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.expense_management_service.entity.ReceiptRelease;
import com.expense_management_service.entity.SupplementaryClaimHistory;
import com.expense_management_service.entity.SupplementaryClaimItem;
import com.expense_management_service.entity.SupplementaryExpenseClaim;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies the supplementary claim entity mappings against the real schema, on a LOCAL database only.
 *
 * <p>Runs only when {@code XMS_LOCAL_DB_URL} is set. The URL must point at localhost and the database must be named
 * {@code xms_schema_check}; anything else fails before any connection is made. It never reads the {@code .env} file,
 * so it can never reach the shared cloud database.
 *
 * <p>{@code XMS_LOCAL_DDL_AUTO} selects the Hibernate mode: {@code update} builds the schema from the entities, and
 * {@code validate} checks the entities against whatever schema is already present (for example the V47 migration).
 */
@DataJpaTest(properties = {
        "spring.datasource.url=${XMS_LOCAL_DB_URL:}",
        "spring.datasource.username=${XMS_LOCAL_DB_USER:}",
        "spring.datasource.password=${XMS_LOCAL_DB_PASS:}",
        "spring.jpa.hibernate.ddl-auto=${XMS_LOCAL_DDL_AUTO:validate}",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "XMS_LOCAL_DB_URL", matches = ".+")
class SupplementarySchemaMappingLocalTest {

    @BeforeAll
    static void refuseAnythingButTheLocalSchemaCheckDatabase() {
        String url = System.getenv("XMS_LOCAL_DB_URL");
        assertThat(url)
                .as("only a localhost test database may be used")
                .matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1):\\d+/xms_schema_check(\\?.*)?");
    }

    @Test
    void entitiesMapToTheSupplementarySchema() {
        // Context startup alone validates every mapping against the schema when ddl-auto=validate.
        // Referencing the types keeps them on the test classpath for the run.
        assertThat(SupplementaryExpenseClaim.class.getSimpleName()).isEqualTo("SupplementaryExpenseClaim");
        assertThat(SupplementaryClaimItem.class.getSimpleName()).isEqualTo("SupplementaryClaimItem");
        assertThat(SupplementaryClaimHistory.class.getSimpleName()).isEqualTo("SupplementaryClaimHistory");
        assertThat(ReceiptRelease.class.getSimpleName()).isEqualTo("ReceiptRelease");
    }
}
