package com.expense_management_service.entity;

import com.expense_management_service.enums.LevelQuorum;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "quorum", length = 255, nullable = false)
    private LevelQuorum quorum;

    @OneToMany(mappedBy = "level", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<ApprovalLevelApprover> approvers = new ArrayList<>();
}
