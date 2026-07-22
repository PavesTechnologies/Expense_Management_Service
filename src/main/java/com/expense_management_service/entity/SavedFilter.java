package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "saved_filter")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class SavedFilter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "filter_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID filterId;

    @Column(name = "employee_id", length = 255, nullable = false)
    private String employeeId;

    @Column(name = "filter_name", length = 255, nullable = false)
    private String filterName;

    @Lob
    @Column(name = "filter_json")
    private String filterJson;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
