package com.expense_management_service.entity;

import com.expense_management_service.enums.LevelInstanceStatus;
import com.expense_management_service.enums.LevelQuorum;
import com.expense_management_service.enums.LevelType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
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

    /** Snapshot copy from the matched {@code ApprovalLevel.levelName} at resolution time - nullable, same fallback rule as the config side ({@code ApprovalFlowMapper.resolveDisplayName}). */
    @Column(name = "level_name", length = 255)
    private String levelName;

    /** Snapshot copy from the matched {@code ApprovalLevel.quorum} at resolution time. */
    @Enumerated(EnumType.STRING)
    @Column(name = "quorum", length = 255, nullable = false)
    private LevelQuorum quorum;

    /**
     * Snapshot copy from the matched {@code ApprovalLevel.levelType} at resolution time - governs
     * which strategy ({@code ApprovalReviewStrategy}/{@code FinanceVerificationStrategy}) and which
     * review table ({@code ApprovalLineItemReview}/{@code FinanceVerificationReview}) this instance
     * is reviewed through. Backfilled to APPROVAL for every level instance materialized before
     * Finance Verification existed (see V13 migration) - never re-read from config afterward, same
     * as every other field on this entity.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "level_type", length = 255, nullable = false)
    @Builder.Default
    private LevelType levelType = LevelType.APPROVAL;

    /** Increments each time a report is resubmitted/restarted (§3.2) - distinguishes a stale prior cycle's rows from the current one. */
    @Column(name = "submission_cycle", nullable = false)
    private Integer submissionCycle;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 255)
    private LevelInstanceStatus status;

    /**
     * The four fields below are a report-level "as of materialization" snapshot, redundantly
     * copied onto every level instance of one materialization pass (no separate "cycle" entity
     * exists to hold a single copy). Read by {@code MaterialChangeEvaluator} at Finance-originated
     * resubmission time to decide resume-Finance-in-place vs restart-at-Manager - a genuinely
     * different question from "does a different ApprovalFlow now match" (§ApprovalFlowResolutionService):
     * a client-billable flip, for instance, is never a flow-matching criterion at all, but is always
     * material here.
     */
    @Column(name = "materialized_total_amount", precision = 19, scale = 4)
    private BigDecimal materializedTotalAmount;

    @Column(name = "materialized_cost_center_id")
    private UUID materializedCostCenterId;

    /** True if any line item was client-billable at materialization time. */
    @Column(name = "materialized_client_billable_any")
    private Boolean materializedClientBillableAny;

    /** Sorted "lineItemId=glAccountId;..." fingerprint at materialization time - a mismatch means at least one line item's GL mapping has since changed. */
    @Lob
    @Column(name = "materialized_gl_account_fingerprint")
    private String materializedGlAccountFingerprint;

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
