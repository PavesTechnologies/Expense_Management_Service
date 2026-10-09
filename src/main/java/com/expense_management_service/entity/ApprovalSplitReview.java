package com.expense_management_service.entity;

import com.expense_management_service.enums.LineItemReviewStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The per-split decision within one combined {@code ApprovalAssignment} - the split-aware sibling of
 * {@code ApprovalLineItemReview}, added alongside it and never replacing it. Normal (non-split)
 * approval levels continue to use {@code ApprovalLineItemReview} exactly as today, completely
 * untouched; this entity exists only for the {@code COST_CENTER_OWNER} responsibilities a report's
 * {@code ExpenseSplit} rows resolve to.
 * <p>
 * Deliberately keyed by {@code (split, assignment)} - not {@code (split, levelInstance)}, unlike
 * {@code ApprovalLineItemReview}'s {@code (lineItem, levelInstance)} keying. This is what makes a
 * combined assignment possible: when the same Cost Center Owner is resolved for multiple splits
 * (whether from the same line item or across different ones), all of them share ONE
 * {@code ApprovalAssignment}, and each gets its own {@code ApprovalSplitReview} child - so "is this
 * assignment done" is answerable per assignment (are all of ITS OWN split reviews APPROVED), never
 * by racing against a shared row a different owner's assignment might also be looking at. This also
 * means an assignment can legitimately reach its own completed state while the level instance as a
 * whole remains active, waiting on a different owner - a state that does not exist for the normal,
 * line-item-keyed review model.
 */
@Entity
@Table(name = "approval_split_review", uniqueConstraints = {
        @UniqueConstraint(name = "uk_approval_split_review_split_assignment", columnNames = {"split_id", "assignment_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ApprovalSplitReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "review_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID reviewId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "split_id", nullable = false)
    @ToString.Exclude
    private ExpenseSplit split;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id", nullable = false)
    @ToString.Exclude
    private ApprovalAssignment assignment;

    /** Reuses ApprovalLineItemReview's own status vocabulary - the same three-state decision, one level deeper. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 255)
    private LineItemReviewStatus status;

    /** Required when {@code status == NEEDS_CORRECTION}; never required for APPROVED. */
    @Lob
    @Column(name = "comment")
    private String comment;

    /** Who actually acted - differs from the assignment's approverId only when a delegate acted. */
    @Column(name = "acted_by", length = 255)
    private String actedBy;

    @Column(name = "actioned_at")
    private LocalDateTime actionedAt;

    /** Optimistic lock - protects against two near-simultaneous actions racing on the same split. */
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
