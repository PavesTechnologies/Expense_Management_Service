package com.expense_management_service.entity;

import com.expense_management_service.enums.ApproverSourceType;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * One approver-source entry configured on an {@link ApprovalLevel}. A level is just a list of these
 * - mixing source types freely on one level, combined with the level's {@code quorum}, is how
 * parallel approval happens (there is no separate parallel-approval mechanism).
 */
@Entity
@Table(name = "approval_level_approver")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ApprovalLevelApprover {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "entry_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID entryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "level_id", nullable = false)
    @ToString.Exclude
    private ApprovalLevel level;

    /** Meaningful only when the parent level's quorum is {@code SEQUENTIAL}; ignored for ANY_OF/ALL_OF. */
    @Column(name = "entry_order")
    private Integer entryOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 255, nullable = false)
    private ApproverSourceType sourceType;

    /** Only set (and only meaningful) when {@code sourceType == NAMED_USER}: the EOS employeeId to use as-is. */
    @Column(name = "source_reference", length = 255)
    private String sourceReference;
}
