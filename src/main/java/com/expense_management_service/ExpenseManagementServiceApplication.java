package com.expense_management_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;


@SpringBootApplication(
    exclude = {
        DataSourceAutoConfiguration.class
    }
)
@ConfigurationPropertiesScan

public class ExpenseManagementServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ExpenseManagementServiceApplication.class, args);
	}

}
