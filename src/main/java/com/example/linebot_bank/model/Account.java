package com.example.linebot_bank.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(
    name = "accounts",
    uniqueConstraints = @UniqueConstraint(name = "uk_line_user_id", columnNames = "line_user_id")
)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "line_user_id", nullable = false, length = 64)
    private String lineUserId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    /** 軟刪除旗標（true=啟用，false=停用/視為刪除） */
    @Column(nullable = false)
    private boolean active = true;

    // ===== Constructors =====
    public Account() { }

    public Account(String lineUserId, String name) {
        this.lineUserId = lineUserId;
        this.name = name;
        this.balance = BigDecimal.ZERO;
        this.active = true;
    }

    // ===== Getters & Setters =====
    public Long getId() { return id; }

    public String getLineUserId() { return lineUserId; }
    public void setLineUserId(String lineUserId) { this.lineUserId = lineUserId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
