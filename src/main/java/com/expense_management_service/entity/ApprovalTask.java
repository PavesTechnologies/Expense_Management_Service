package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "approval_task")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ApprovalTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "task_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID taskId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    @ToString.Exclude
    private ExpenseReport report;

    @Column(name = "approver_id", length = 255, nullable = false)
    private String approverId;

    @Column(name = "approval_level")
    private Integer approvalLevel;

    @Column(name = "task_status", length = 255)
    private String taskStatus;

    @Lob
    @Column(name = "comments")
    private String comments;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "actioned_at")
    private LocalDateTime actionedAt;

    @Column(name = "due_date")
    private LocalDateTime dueDate;
}
