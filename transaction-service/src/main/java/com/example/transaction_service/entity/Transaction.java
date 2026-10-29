package com.example.transaction_service.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
    name = "transactions",
    indexes = {
        @Index(name = "idx_txn_source_account", columnList = "sourceAccountNumber"),
        @Index(name = "idx_txn_target_account", columnList = "targetAccountNumber"),
        @Index(name = "idx_txn_status", columnList = "status"),
        @Index(name = "idx_txn_idempotency_key", columnList = "idempotencyKey", unique = true),
        @Index(name = "idx_txn_reference_number", columnList = "referenceNumber", unique = true)
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Version
    private Long version;

    /**
     * Unique client idempotency key to prevent double-charging on network retries.
     */
    @Column(unique = true, length = 100)
    private String idempotencyKey;

    @Column(nullable = false)
    private String sourceAccountNumber;

    @Column(nullable = false)
    private String targetAccountNumber;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    private String description;

    private String failureReason;

    @Column(unique = true)
    private String referenceNumber;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;
}
