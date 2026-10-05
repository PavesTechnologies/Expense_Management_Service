package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "project_cache", uniqueConstraints = @UniqueConstraint(columnNames = "project_code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ProjectCache {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "project_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID projectId;

    @Column(name = "project_code", length = 255, nullable = false)
    private String projectCode;

    @Column(name = "project_name", length = 255, nullable = false)
    private String projectName;

    @Column(name = "client_name", length = 255)
    private String clientName;

    @Column(name = "status", length = 255)
    private String status;

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    /**
     * PMS's own stable numeric project id (their {@code Project.id}, a BIGINT identity — never
     * their mutable {@code projectKey}). Null for any row created before this field existed via
     * the legacy manual admin API ({@code ProjectCacheController}). Indexed uniquely (see
     * {@code V18__project_cache_pms_client_index.sql}) but nullable, since MySQL permits multiple
     * NULLs under a unique index — legacy manually-entered rows are not required to carry it.
     */
    @Column(name = "pms_project_id")
    private Long pmsProjectId;

    /**
     * RMS client UUID ({@code client.client_id}), resolved from PMS's {@code Project.clientId} at
     * the moment this row was last refreshed. This is a live-refreshable cache value, not an
     * audit record — {@code ExpenseLineItem.resolvedClientId}/{@code resolvedClientName} hold the
     * frozen snapshot taken at submission time instead.
     */
    @Column(name = "client_id")
    private UUID clientId;

    @OneToMany(mappedBy = "project")
    @Builder.Default
    @ToString.Exclude
    private List<ExpenseLineItem> expenseLineItems = new ArrayList<>();
}
