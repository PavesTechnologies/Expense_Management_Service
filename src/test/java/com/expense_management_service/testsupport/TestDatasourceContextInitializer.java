package com.expense_management_service.testsupport;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;

/**
 * Runs after the {@code .env} loader (which is an initializer too). It re-applies the isolation so that a value loaded
 * from {@code .env} can never be the one a test datasource uses, and it re-checks the final URL.
 */
public class TestDatasourceContextInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        TestDatasourceIsolation.apply(context.getEnvironment());
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
