package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Local mirror of employee/manager data sourced from the Employee Onboarding
 * System (EOS), kept in sync via the Employee CDC pipeline
 * ({@code consumer.EmployeeCdcConsumer}). Follows the same external-data-cache
 * convention as {@link ProjectCache}.
 *
 * <p>{@code managerEmployeeId} deliberately mirrors an EOS data quirk: EOS's
 * {@code employee_details.reporting_manager_uuid} column actually stores the
 * manager's {@code employee_id}, not a UUID, despite its name and CHAR(36)
 * type - and carries no foreign key. Any lookup against it must join on
 * {@code employeeId}, never {@code employeeUuid}.</p>
 */
@Entity
@Table(name = "employee_cache", uniqueConstraints = {
        @UniqueConstraint(columnNames = "employee_id"),
        @UniqueConstraint(columnNames = "employee_uuid")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class EmployeeCache {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "cache_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID cacheId;

    /** EOS's human-readable business id (e.g. "5100101"). Stable join key for manager resolution. */
    @Column(name = "employee_id", length = 20, nullable = false)
    private String employeeId;

    /** EOS's UUID - the CDC correlation key (the Debezium message key). */
    @Column(name = "employee_uuid", length = 36, nullable = false)
    private String employeeUuid;

    @Column(name = "first_name", length = 50)
    private String firstName;

    @Column(name = "last_name", length = 50)
    private String lastName;

    @Column(name = "work_email", length = 100)
    private String workEmail;

    /**
     * The {@code employeeId} (NOT {@code employeeUuid}) of this employee's
     * manager - see class Javadoc. May be null (no manager assigned) or
     * reference an employee not yet present in this cache.
     */
    @Column(name = "manager_employee_id", length = 20)
    private String managerEmployeeId;

    @Column(name = "department_uuid", length = 36)
    private String departmentUuid;

    @Column(name = "designation_uuid", length = 36)
    private String designationUuid;

    /**
     * Raw EOS {@code employment_status} value (e.g. "Active", "On-Notice",
     * "Exited"). Kept verbatim; interpreting it for approval-workflow
     * purposes happens in the workflow layer, not here.
     */
    @Column(name = "employment_status", length = 50)
    private String employmentStatus;

    @Column(name = "employment_type", length = 50)
    private String employmentType;

    @Column(name = "joining_date")
    private LocalDate joiningDate;

    /** When this row was last written by the CDC consumer. */
    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
