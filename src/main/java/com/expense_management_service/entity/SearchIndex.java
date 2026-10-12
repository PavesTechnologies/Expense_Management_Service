package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "search_index", indexes = {
        @Index(name = "idx_search_query", columnList = "search_query"),
        @Index(name = "idx_employee_id", columnList = "employee_id"),
        @Index(name = "idx_report_id", columnList = "report_id"),
        @Index(name = "idx_created_at", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class SearchIndex {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "search_index_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID searchIndexId;

    @Column(name = "report_id", nullable = false)
    private UUID reportId;

    @Column(name = "report_number", length = 255)
    private String reportNumber;

    @Column(name = "employee_id", length = 255, nullable = false)
    private String employeeId;

    @Column(name = "employee_name", length = 255)
    private String employeeName;

    @Column(name = "merchant", length = 500)
    private String merchant;

    @Column(name = "category", length = 255)
    private String category;

    @Column(name = "cost_center", length = 255)
    private String costCenter;

    @Column(name = "business_purpose", columnDefinition = "TEXT")
    private String businessPurpose;

    @Column(name = "status", length = 100)
    private String status;

    @Column(name = "amount", nullable = false)
    private Double amount;

    @Column(name = "search_query", length = 500, nullable = false)
    private String searchQuery;

    @Column(name = "search_type", length = 100)
    private String searchType;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
