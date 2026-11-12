package com.expense_management_service.entity;

import com.expense_management_service.enums.LineItemReviewStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The actual per-line-item decision within one {@link ApprovalLevelInstance} (§4.7) - this is the
 * real unit of approver action. {@code ExpenseLineItem} itself is untouched by this engine; this is
 * a pure join entity. A level completes only once every line item on the report reaches
 * {@code APPROVED} here.
 */
@Entity
@Table(name = "approval_line_item_review")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ApprovalLineItemReview {

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
    private LineItemReviewStatus status;

    /** Required when {@code status == NEEDS_CORRECTION} (§4.2); never required for APPROVED (§10.2, silent approval). */
    @Lob
    @Column(name = "comment")
    private String comment;

    /** Who actually acted - differs from the assignment's approverId only when a delegate acted. */
    @Column(name = "acted_by", length = 255)
    private String actedBy;

    @Column(name = "actioned_at")
    private LocalDateTime actionedAt;

    /** Optimistic lock - protects against, e.g., two near-simultaneous actions racing on the same line item (§4.6). */
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
