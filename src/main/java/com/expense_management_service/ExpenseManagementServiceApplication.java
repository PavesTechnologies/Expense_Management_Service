package com.expense_management_service;

import me.paulschwarz.springdotenv.spring.DotenvApplicationInitializer;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;


@SpringBootApplication
@ConfigurationPropertiesScan
public class ExpenseManagementServiceApplication {

	public static void main(String[] args) {
		new SpringApplicationBuilder(ExpenseManagementServiceApplication.class)
				.initializers(new DotenvApplicationInitializer())
				.run(args);
	}

}
