package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A log entry marking the moment a {@link Policy}'s rule content changed - not a content snapshot.
 * The rule content itself doesn't need duplicating here: every {@link PolicyViolation} already
 * denormalises everything it needs (ruleType, severity, enforcementType, message, limit/actual
 * values, severityTier, currency) at detection time, specifically so it renders correctly forever
 * regardless of later edits. This table exists only to answer "which numbered version was active
 * when," stamped onto {@code PolicyViolation#policyVersionNumber}. A policy with zero rows here is
 * implicitly still at its original version 1 - see {@code PolicyVersionServiceImpl#getCurrentVersion}.
 * Who made the change and what exactly changed is answered by the existing generic {@code
 * AuditLog}, not duplicated here.
 */
@Entity
@Table(name = "policy_version")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class PolicyVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "version_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID versionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id", nullable = false)
    @ToString.Exclude
    private Policy policy;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "activated_at", nullable = false)
    private LocalDateTime activatedAt;
}
