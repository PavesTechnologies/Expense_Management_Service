package com.expense_management_service;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest
class ExpenseManagementServiceApplicationTests {

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;

    /** The default test context must use the isolated in-memory database, never the shared cloud host. */
    @org.junit.jupiter.api.Test
    void datasourceIsIsolatedInMemory_andNeverTheSharedCloudHost() {
        String url = environment.getProperty("spring.datasource.url", "");
        org.assertj.core.api.Assertions.assertThat(url).startsWith("jdbc:h2:mem:xms_test");
        org.assertj.core.api.Assertions.assertThat(url.toLowerCase(java.util.Locale.ROOT)).doesNotContain("aivencloud");
        org.assertj.core.api.Assertions.assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                .isEqualTo("create-drop");
    }


	@Test
	void contextLoads() {
	}
}

