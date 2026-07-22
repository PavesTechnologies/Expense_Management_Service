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

    @OneToMany(mappedBy = "project")
    @Builder.Default
    @ToString.Exclude
    private List<ExpenseLineItem> expenseLineItems = new ArrayList<>();
}
