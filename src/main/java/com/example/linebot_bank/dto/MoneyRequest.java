package com.example.linebot_bank.dto;

import java.math.BigDecimal;

public class MoneyRequest {
    private String lineUserId;
    private BigDecimal amount;
    private String note;

    public String getLineUserId() { return lineUserId; }
    public void setLineUserId(String lineUserId) { this.lineUserId = lineUserId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
