package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A priority-ordered approval routing rule: criteria (when it applies) + an ordered list of
 * {@link ApprovalLevel}s. Flows are evaluated in ascending {@code priority} order; the first whose
 * criteria match wins, and only that one flow runs for a given report. Exactly one flow has
 * {@code isCatchAll = true} - it always evaluates last regardless of its {@code priority} value,
 * has no criteria (always matches), and guarantees every report resolves to a chain, replacing what
 * would otherwise be a "submission blocked, nothing matched" dead end.
 */
@Entity
@Table(name = "approval_flow")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ApprovalFlow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "flow_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID flowId;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    /** Ascending = evaluated first. Ignored for the catch-all flow, which always evaluates last. */
    @Column(name = "priority")
    private Integer priority;

    /**
     * Boolean pattern referencing {@code ApprovalFlowCriterion.index} values, e.g. {@code "(1 AND 2) OR 3"}.
     * Null/blank for the catch-all flow, which has no criteria and always matches.
     */
    @Lob
    @Column(name = "criteria_pattern")
    private String criteriaPattern;

    /**
     * Exactly one row has this true. Cannot be deleted, must always have &ge;1 level (no
     * auto-approve - every report gets human review), and always evaluates last regardless of
     * {@code priority}.
     */
    @Column(name = "is_catch_all", nullable = false)
    private Boolean isCatchAll;

    @Column(name = "status", length = 255)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "flow", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<ApprovalFlowCriterion> criteria = new ArrayList<>();

    @OneToMany(mappedBy = "flow", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<ApprovalLevel> levels = new ArrayList<>();
}
