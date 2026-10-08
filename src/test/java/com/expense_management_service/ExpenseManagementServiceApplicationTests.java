package com.expense_management_service;
import me.paulschwarz.springdotenv.spring.DotenvApplicationInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest
@ContextConfiguration(initializers = DotenvApplicationInitializer.class)
class ExpenseManagementServiceApplicationTests {

	@Test
	void contextLoads() {
	}
}

