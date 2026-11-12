package com.expense_management_service.entity;

import com.expense_management_service.enums.LevelQuorum;
import com.expense_management_service.enums.LevelType;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One ordered stage within an {@link ApprovalFlow}'s configuration. Soft-capped at 10 levels per
 * flow (sanity guardrail, not a real-world constraint) - enforced in service-layer validation, not
 * here.
 */
@Entity
@Table(name = "approval_level")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ApprovalLevel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "level_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID levelId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flow_id", nullable = false)
    @ToString.Exclude
    private ApprovalFlow flow;

    @Column(name = "level_order", nullable = false)
    private Integer levelOrder;

    /** Optional display label (e.g. "Manager Review"). Falls back to "Level " + levelOrder wherever shown when blank - that fallback is computed at the DTO layer, never stored. */
    @Column(name = "level_name", length = 255)
    private String levelName;

    @Enumerated(EnumType.STRING)
    @Column(name = "quorum", length = 255, nullable = false)
    private LevelQuorum quorum;

    /** Defaults to APPROVAL - existing flows configured before Finance Verification existed behave exactly as before. */
    @Enumerated(EnumType.STRING)
    @Column(name = "level_type", length = 255, nullable = false)
    @Builder.Default
    private LevelType levelType = LevelType.APPROVAL;

    /** Same rationale as {@code ApprovalFlow.levels}' {@code @OrderBy} - {@code entry_id} is a random UUID, so this must be explicit or a SEQUENTIAL level's approver order can come back scrambled on a fresh fetch. Nulls (ANY_OF/ALL_OF entries, where order is meaningless) sort first under MySQL's default ASC null ordering, which is harmless since nothing depends on their relative order. */
    @OneToMany(mappedBy = "level", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("entryOrder ASC")
    @Builder.Default
    @ToString.Exclude
    private List<ApprovalLevelApprover> approvers = new ArrayList<>();
}
