package com.example.linebot_bank.dto;

public class RegisterRequest {
    private String lineUserId;
    private String name;

    public String getLineUserId() { return lineUserId; }
    public void setLineUserId(String lineUserId) { this.lineUserId = lineUserId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
