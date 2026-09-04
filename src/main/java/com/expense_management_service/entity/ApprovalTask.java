package com.expense_management_service.entity;

import com.expense_management_service.enums.ApprovalMode;
import com.expense_management_service.enums.TaskStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "approval_task")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ApprovalTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "task_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID taskId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    @ToString.Exclude
    private ExpenseReport report;

    @Column(name = "approver_id", length = 255, nullable = false)
    private String approverId;

    @Column(name = "approval_level")
    private Integer approvalLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_status", length = 255)
    private TaskStatus taskStatus;

    @Lob
    @Column(name = "comments")
    private String comments;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "actioned_at")
    private LocalDateTime actionedAt;

    @Column(name = "due_date")
    private LocalDateTime dueDate;

    /** Clusters sibling tasks materialised together at the same level (see EP06 plan, "snapshot at submission"). */
    @Column(name = "group_id")
    private UUID groupId;

    /**
     * Copied from the resolved ApprovalMatrix row at submission time - there is no FK back to
     * ApprovalMatrix, so this is how level-completion logic (sequential vs. parallel-any vs.
     * parallel-all) is determined without a live join. Consistent with the snapshot design: the
     * whole resolved plan, including its mode, is frozen onto the task rows, not re-derived later.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_mode", length = 255)
    private ApprovalMode approvalMode;

    /** Increments each time a report is resubmitted; distinguishes a stale rejected-cycle row from the current one. */
    @Column(name = "submission_cycle")
    private Integer submissionCycle;

    /** Set only when a delegate acts on a task still assigned to approverId - see EP06 plan, Phase 3. */
    @Column(name = "acted_by", length = 255)
    private String actedBy;

    /** Optimistic lock - protects against, e.g., an approve and a reject racing on the same task. */
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
