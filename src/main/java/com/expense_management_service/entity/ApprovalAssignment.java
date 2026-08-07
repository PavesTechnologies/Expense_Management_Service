package com.expense_management_service.entity;

import com.expense_management_service.enums.ApproverSourceType;
import com.expense_management_service.enums.AssignmentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One resolved approver-source entry, snapshotted onto a specific {@link ApprovalLevelInstance}.
 * {@code sourceType} is copied for audit/display only - the resolved {@code approverId} is what
 * actually governs who may act (checked per-task, never by role - §1.5).
 */
@Entity
@Table(name = "approval_assignment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ApprovalAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "assignment_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID assignmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instance_id", nullable = false)
    @ToString.Exclude
    private ApprovalLevelInstance levelInstance;

    /** The resolved EOS employeeId - what actually governs who may act on this level. */
    @Column(name = "approver_id", length = 255, nullable = false)
    private String approverId;

    /** Snapshot copy, for audit/display only. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 255)
    private ApproverSourceType sourceType;

    /** Meaningful only when the parent level instance's quorum is SEQUENTIAL. */
    @Column(name = "entry_order")
    private Integer entryOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 255)
    private AssignmentStatus status;

    /** Set only when this assignment superseded a prior one after an account-removal re-resolution (§5.5). */
    @Column(name = "superseded_approver_id", length = 255)
    private String supersededApproverId;

    /** Set when the assignment becomes ACTIVE - the SLA clock starts only then, not at materialisation. */
    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    /** {@code assignedAt + SlaPolicyService.resolveSlaBusinessDays()} business days - reminders-only past this (§5.4), never auto-escalated. */
    @Column(name = "due_date")
    private LocalDateTime dueDate;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
