package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Admin-curated mapping of department &rarr; approver, backing {@code ApproverSourceType.DEPARTMENT_OWNER}.
 * New concept - no "department head" field exists anywhere upstream (Department is remote-only
 * master data via {@code DepartmentClient}, with just a uuid/name/description), so this is
 * deliberately admin-set, like Fyle's "Department Approver".
 */
@Entity
@Table(name = "department_approver", uniqueConstraints = {
        @UniqueConstraint(columnNames = "department_uuid")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class DepartmentApprover {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "department_approver_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID departmentApproverId;

    /**
     * UUID of the Department record in Employee Onboarding (same reference EMS stores elsewhere,
     * e.g. {@code CostCenter.departmentUuid}). Note {@code EmployeeCache.departmentUuid} is a
     * {@code String} - callers resolving from an employee must convert via {@code UUID.fromString}.
     */
    @Column(name = "department_uuid", nullable = false)
    private UUID departmentUuid;

    /** EOS employeeId of the approver for this department. */
    @Column(name = "approver_employee_id", length = 255, nullable = false)
    private String approverEmployeeId;

    @Column(name = "status", length = 255)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
