package com.expense_management_service.entity;

import com.expense_management_service.enums.PolicyAssignmentType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Connects a {@link Policy} to whoever it governs. Exactly one {@code DEFAULT} row must always
 * exist system-wide (seeded by the Phase 1 migration, never deleted) — it is the resolver's final
 * fallback. {@code INDIVIDUAL} rows carry {@code employeeId}; {@code GROUP} rows carry {@code
 * group}; see {@code PolicyAssignmentResolver} for the Individual &gt; Group &gt; Default
 * precedence that reads these.
 */
@Entity
@Table(name = "policy_assignment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class PolicyAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "assignment_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID assignmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_type", length = 255, nullable = false)
    private PolicyAssignmentType assignmentType;

    /**
     * Populated only when {@code assignmentType == INDIVIDUAL}; the raw UMS employee id, matching
     * how {@code ExpenseReport.employeeId}/{@code ApprovalTask.approverId} reference employees
     * elsewhere in this codebase (no local Employee entity/FK exists to point at instead).
     */
    @Column(name = "employee_id", length = 255)
    private String employeeId;

    /** Populated only when {@code assignmentType == GROUP}. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    @ToString.Exclude
    private PolicyGroup group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id", nullable = false)
    @ToString.Exclude
    private Policy policy;

    @Column(name = "status", length = 255)
    private String status;

    @Version
    @Column(name = "version")
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
