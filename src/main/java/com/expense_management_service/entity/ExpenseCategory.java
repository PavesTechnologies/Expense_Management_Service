package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "expense_category", uniqueConstraints = @UniqueConstraint(columnNames = "category_code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class ExpenseCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "category_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID categoryId;

    @Column(name = "category_code", length = 255, nullable = false)
    private String categoryCode;

    @Column(name = "category_name", length = 255, nullable = false)
    private String categoryName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gl_account_id", nullable = false)
    @ToString.Exclude
    private GlAccount glAccount;

    @Lob
    @Column(name = "description")
    private String description;

    @Column(name = "receipt_required")
    private Boolean receiptRequired;

    @Column(name = "max_limit", precision = 19, scale = 4)
    private BigDecimal maxLimit;

    @Column(name = "status", length = 255)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "category")
    @Builder.Default
    @ToString.Exclude
    private List<PolicyRule> policyRules = new ArrayList<>();

    @OneToMany(mappedBy = "category")
    @Builder.Default
    @ToString.Exclude
    private List<ExpenseLineItem> expenseLineItems = new ArrayList<>();
}
