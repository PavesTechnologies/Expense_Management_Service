package com.expense_management_service.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;

/**
 * The clock every date-dependent validation reads, so "today" is the business day in the business timezone.
 * <p>
 * Production uses the real current time. A development-only override moves the date while keeping the clock
 * running. It is off unless {@code expense.clock.override-enabled=true} is set in the environment. That property is
 * read once at startup, and there is no endpoint that changes it, so an ordinary user cannot enable it. The
 * application refuses to start with the override on under the {@code prod} profile.
 */
@Slf4j
@Configuration
public class BusinessClockConfig {

    @Bean
    public Clock businessClock(@Value("${expense.business-time-zone:Asia/Kolkata}") String zone,
                               @Value("${expense.clock.override-enabled:false}") boolean overrideEnabled,
                               @Value("${expense.clock.override-date:}") String overrideDate,
                               @Value("${spring.profiles.active:}") String activeProfiles) {
        return resolve(ZoneId.of(zone), overrideEnabled, overrideDate, activeProfiles, Instant.now());
    }

    /** The clock for the given settings. Separated out so the rules can be tested without a Spring context. */
    static Clock resolve(ZoneId zone, boolean overrideEnabled, String overrideDate, String activeProfiles, Instant now) {
        if (!overrideEnabled) {
            return Clock.system(zone);
        }
        boolean production = Arrays.stream(activeProfiles.split(","))
                .map(String::trim)
                .anyMatch("prod"::equalsIgnoreCase);
        if (production) {
            throw new IllegalStateException("expense.clock.override-enabled must be false when the prod profile is active.");
        }
        if (overrideDate == null || overrideDate.isBlank()) {
            return Clock.system(zone);
        }
        LocalDate day = LocalDate.parse(overrideDate.trim());
        ZonedDateTime today = ZonedDateTime.ofInstant(now, zone);
        Instant target = day.atTime(today.toLocalTime()).atZone(zone).toInstant();
        log.warn("Business date overridden to {} for development. Keep expense.clock.override-enabled off outside development.", day);
        return Clock.offset(Clock.system(zone), Duration.between(now, target)).withZone(zone);
    }
}
