package com.expense_management_service.entity;

import com.expense_management_service.enums.LevelInstanceStatus;
import com.expense_management_service.enums.LevelQuorum;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One resolved, snapshotted level for a specific report and submission cycle - this IS
 * "snapshot-at-submission" (§3.1): {@code levelOrder}/{@code quorum} are copied from the matched
 * {@link com.expense_management_service.entity.ApprovalLevel} config at resolution time and never
 * re-read from it afterward, so a later config edit can never affect an in-progress report.
 * Replaces EP06's {@code groupId} concept.
 */
@Entity
@Table(name = "approval_level_instance")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ApprovalLevelInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "instance_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID instanceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    @ToString.Exclude
    private ExpenseReport report;

    /**
     * Plain UUID, not a JPA relation (a flow may later be deleted independently of report
     * history). Records which {@code ApprovalFlow} matched at resolution time so a correction
     * resubmission can compare "does the same flow still match" (§2.8) without re-deriving it from
     * level structure, which could coincidentally collide between two different flows.
     */
    @Column(name = "flow_id", nullable = false)
    private UUID flowId;

    /** Snapshot copy from the matched {@code ApprovalLevel.levelOrder} at resolution time. */
    @Column(name = "level_order", nullable = false)
    private Integer levelOrder;

    /** Snapshot copy from the matched {@code ApprovalLevel.quorum} at resolution time. */
    @Enumerated(EnumType.STRING)
    @Column(name = "quorum", length = 255, nullable = false)
    private LevelQuorum quorum;

    /** Increments each time a report is resubmitted/restarted (§3.2) - distinguishes a stale prior cycle's rows from the current one. */
    @Column(name = "submission_cycle", nullable = false)
    private Integer submissionCycle;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 255)
    private LevelInstanceStatus status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "levelInstance", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<ApprovalAssignment> assignments = new ArrayList<>();

    @OneToMany(mappedBy = "levelInstance", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<ApprovalLineItemReview> lineItemReviews = new ArrayList<>();
}
