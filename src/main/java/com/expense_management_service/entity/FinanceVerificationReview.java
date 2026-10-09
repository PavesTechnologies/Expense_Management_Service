package com.expense_management_service.entity;

import com.expense_management_service.enums.FinanceVerificationStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The Finance-level equivalent of {@link ApprovalLineItemReview} - one per line item, per
 * FINANCE_VERIFICATION {@code ApprovalLevelInstance}. Kept as a separate entity/table rather than
 * overloading {@code ApprovalLineItemReview} because Finance review carries audit fields ({@link
 * #glAccountIdAtVerification}/{@link #glAccountCodeSnapshot}) a Manager review never needs, and
 * because it must be distinguishable in read models (approval history, "My History") from a
 * Manager decision on the same line item.
 * <p>
 * Same documented quorum simplification as {@code ApprovalLineItemReview}: keyed by (lineItem,
 * levelInstance), not (lineItem, levelInstance, assignment) - ALL_OF and ANY_OF behave identically
 * today (first entry to finish a full pass completes the level). See {@code
 * ApprovalWorkflowServiceImpl}'s class javadoc for the full explanation; this entity inherits the
 * same limitation deliberately rather than redesigning review storage as part of this feature.
 */
@Entity
@Table(name = "finance_verification_review")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class FinanceVerificationReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "review_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID reviewId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "line_item_id", nullable = false)
    @ToString.Exclude
    private ExpenseLineItem lineItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instance_id", nullable = false)
    @ToString.Exclude
    private ApprovalLevelInstance levelInstance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 255)
    private FinanceVerificationStatus status;

    /**
     * Bare UUID, not a JPA relation - deliberately mirrors {@code ApprovalLevelInstance.flowId}:
     * the GL account referenced at verification time must remain readable even if an admin later
     * archives/deletes that {@code GlAccount} row. {@link #glAccountCodeSnapshot} is what actually
     * renders in the audit trail; this id is kept for traceability while the row still exists.
     */
    @Column(name = "gl_account_id_at_verification")
    private UUID glAccountIdAtVerification;

    @Column(name = "gl_account_code_snapshot", length = 255)
    private String glAccountCodeSnapshot;

    /** Snapshot of the eligibility checker's policy-exception verdict at the moment of VERIFY. */
    @Column(name = "policy_exception_resolved_flag")
    private Boolean policyExceptionResolvedFlag;

    /** Snapshot of the eligibility checker's receipt verdict at the moment of VERIFY. */
    @Column(name = "receipt_validated_flag")
    private Boolean receiptValidatedFlag;

    /** Required when {@code status == QUERIED}; never required for VERIFIED - same convention as {@code ApprovalLineItemReview.comment}. */
    @Lob
    @Column(name = "comment")
    private String comment;

    /** Who actually acted - differs from the assignment's approverId only when a delegate acted. */
    @Column(name = "acted_by", length = 255)
    private String actedBy;

    @Column(name = "actioned_at")
    private LocalDateTime actionedAt;

    /** Optimistic lock - protects against two Finance users acting on the same line item near-simultaneously. */
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
