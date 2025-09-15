package com.example.linebot_bank.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionType type;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(length = 200)
    private String note;

    public Transaction() {}

    public Transaction(Account account, TransactionType type, BigDecimal amount, String note) {
        this.account = account;
        this.type = type;
        this.amount = amount;
        this.note = note;
        this.createdAt = LocalDateTime.now();
    }

    // Getters
    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public TransactionType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getNote() { return note; }
}