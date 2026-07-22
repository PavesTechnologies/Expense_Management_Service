package com.expense_management_service.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "receipt")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class Receipt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "receipt_id", updatable = false, nullable = false)
    @EqualsAndHashCode.Include
    private UUID receiptId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "line_item_id", nullable = false)
    @ToString.Exclude
    private ExpenseLineItem lineItem;

    @Column(name = "file_name", length = 255, nullable = false)
    private String fileName;

    @Column(name = "file_path", length = 255)
    private String filePath;

    @Column(name = "file_type", length = 255)
    private String fileType;

    @Column(name = "file_size")
    private Integer fileSize;

    @Column(name = "uploaded_by", length = 255)
    private String uploadedBy;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    @Column(name = "ocr_status", length = 255)
    private String ocrStatus;

    @Column(name = "file_hash", length = 255)
    private String fileHash;

    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @ToString.Exclude
    private List<ReceiptOcr> receiptOcrs = new ArrayList<>();
}
