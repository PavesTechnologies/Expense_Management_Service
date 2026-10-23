package com.expense_management_service.e2e;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** The whole application starts on the in-memory database, and never on the shared MySQL. */
class E2eContextTest extends E2eTestBase {

    @Autowired
    private DataSource dataSource;

    @Test
    void applicationStartsOnTheInMemoryDatabase() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
        }
    }
}
