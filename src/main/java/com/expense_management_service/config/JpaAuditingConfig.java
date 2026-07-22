package com.expense_management_service.config;

import com.expense_management_service.security.SecurityUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;

import java.util.UUID;

/**
 * Wires Spring Data JPA auditing to the UMS identity carried in the current JWT.
 * <p>
 * Entities that annotate columns with {@code @CreatedBy}/{@code @LastModifiedBy}
 * (e.g. {@code created_by_uuid}, {@code updated_by_uuid}) get them populated
 * automatically with {@code obs_user_uuid} — never the numeric UMS {@code user_id}.
 * <p>
 * Columns that represent a workflow action rather than a create/update audit trail
 * (e.g. {@code approved_by_uuid}, {@code finance_verified_by_uuid},
 * {@code submitted_by_uuid}) are <b>not</b> covered by this auditor and must be set
 * explicitly by service code at the point that action occurs, via
 * {@code CurrentUserService.getUserUuid()}.
 * <p>
 * <b>{@code @EnableJpaAuditing} is intentionally NOT applied here yet.</b> That
 * annotation eagerly builds a {@code JpaMetamodelMappingContext}, which requires
 * at least one {@code @Entity} to exist and a working {@code DataSource}/
 * {@code EntityManagerFactory} — neither exists yet in this project (no expense
 * entities, and {@code DataSourceAutoConfiguration} is still excluded), so enabling
 * it now fails application startup with "JPA metamodel must not be empty". Add
 * {@code @EnableJpaAuditing(auditorAwareRef = "auditorAware")} to this class once
 * the first {@code @Entity} and a real datasource are introduced.
 */
@Configuration
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<UUID> auditorAware() {
        return SecurityUtils::currentUserUuid;
    }
}
