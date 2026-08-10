package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "cost_center", uniqueConstraints = {
        @UniqueConstraint(columnNames = "cost_center_code"),
        @UniqueConstraint(columnNames = {"department_uuid", "cost_center_name"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class CostCenter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "cost_center_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID costCenterId;

    @Column(name = "cost_center_code", length = 255, nullable = false)
    private String costCenterCode;

    @Column(name = "cost_center_name", length = 255, nullable = false)
    private String costCenterName;

    /** UUID of the owning Department record in Employee Onboarding — EMS stores only the reference, never the department name. */
    @Column(name = "department_uuid", nullable = false)
    private UUID departmentUuid;

    @Column(name = "description", length = 1000)
    private String description;

    /**
     * The owning employee's EOS {@code employeeId} - the same identifier space as every other
     * approver reference in this system (EmployeeCache.managerEmployeeId, ApprovalDelegation
     * delegator/delegate, approval-flow approver sources). Previously stored a UMS {@code user_id}
     * instead (a different identifier space), which meant COST_CENTER_OWNER approver resolution
     * could never resolve to a real employee - fixed alongside the V6 migration.
     */
    @Column(name = "owner_employee_id", length = 255)
    private String ownerEmployeeId;

    @Column(name = "status", length = 255)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "costCenter")
    @Builder.Default
    @ToString.Exclude
    private List<CostCenterBudget> costCenterBudgets = new ArrayList<>();

    @OneToMany(mappedBy = "costCenter")
    @Builder.Default
    @ToString.Exclude
    private List<ExpenseReport> expenseReports = new ArrayList<>();

    @OneToMany(mappedBy = "costCenter")
    @Builder.Default
    @ToString.Exclude
    private List<ExpenseLineItem> expenseLineItems = new ArrayList<>();

    @OneToMany(mappedBy = "costCenter")
    @Builder.Default
    @ToString.Exclude
    private List<CostAllocation> costAllocations = new ArrayList<>();
}
