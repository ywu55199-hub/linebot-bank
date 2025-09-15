package com.example.linebot_bank.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "account") // ⚠️ 改成單數，對應資料表 account
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "line_user_id", nullable = false, unique = true)
    private String lineUserId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    // === 建構子 ===
    public Account() {}

    public Account(String lineUserId, String name) {
        this.lineUserId = lineUserId;
        this.name = name;
        this.balance = BigDecimal.ZERO;
        this.active = true;
    }

    // === Getter & Setter ===
    public Long getId() {
        return id;
    }

    public String getLineUserId() {
        return lineUserId;
    }

    public void setLineUserId(String lineUserId) {
        this.lineUserId = lineUserId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
