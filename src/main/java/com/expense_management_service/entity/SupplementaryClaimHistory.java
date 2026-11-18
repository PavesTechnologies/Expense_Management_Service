package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/** Append-only audit trail for a supplementary claim. Rows are never updated or deleted. */
@Entity
@Table(name = "supplementary_claim_history")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupplementaryClaimHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "history_id", updatable = false, nullable = false)
    private UUID historyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_id", nullable = false)
    private SupplementaryExpenseClaim claim;

    @Column(name = "actor_id", nullable = false, length = 255)
    private String actorId;

    @Column(name = "actor_role", length = 64)
    private String actorRole;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "previous_status", length = 32)
    private String previousStatus;

    @Column(name = "new_status", length = 32)
    private String newStatus;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
