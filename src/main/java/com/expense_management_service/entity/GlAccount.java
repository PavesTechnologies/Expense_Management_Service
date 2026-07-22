package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "gl_account", uniqueConstraints = @UniqueConstraint(columnNames = "gl_account_code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class GlAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "gl_account_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID glAccountId;

    @Column(name = "gl_account_code", length = 255, nullable = false)
    private String glAccountCode;

    @Column(name = "gl_account_name", length = 255, nullable = false)
    private String glAccountName;

    @Column(name = "account_type", length = 255)
    private String accountType;

    @Lob
    @Column(name = "description")
    private String description;

    @Column(name = "status", length = 255)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "glAccount")
    @Builder.Default
    @ToString.Exclude
    private List<ExpenseCategory> expenseCategories = new ArrayList<>();
}
