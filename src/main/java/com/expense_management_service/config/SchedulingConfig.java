package com.expense_management_service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables {@code @Scheduled} beans (currently: the daily exchange-rate refresh job). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
