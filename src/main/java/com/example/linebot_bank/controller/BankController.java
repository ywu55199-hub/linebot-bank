package com.example.linebot_bank.controller;

import com.example.linebot_bank.dto.MoneyRequest;
import com.example.linebot_bank.dto.RegisterRequest;
import com.example.linebot_bank.model.Transaction;
import com.example.linebot_bank.service.BankService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bank")
public class BankController {

    private final BankService bankService;

    public BankController(BankService bankService) {
        this.bankService = bankService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        var acc = bankService.register(req.getLineUserId(), req.getName());
        Map<String, Object> resp = new HashMap<>();
        resp.put("lineUserId", acc.getLineUserId());
        resp.put("name", acc.getName());
        resp.put("balance", acc.getBalance());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/balance")
    public ResponseEntity<?> balance(@RequestParam String lineUserId) {
        BigDecimal bal = bankService.getBalance(lineUserId);
        Map<String, Object> resp = new HashMap<>();
        resp.put("lineUserId", lineUserId);
        resp.put("balance", bal);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/deposit")
    public ResponseEntity<?> deposit(@RequestBody MoneyRequest req) {
        BigDecimal bal = bankService.deposit(req.getLineUserId(), req.getAmount(), req.getNote());
        Map<String, Object> resp = new HashMap<>();
        resp.put("lineUserId", req.getLineUserId());
        resp.put("newBalance", bal);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/withdraw")
    public ResponseEntity<?> withdraw(@RequestBody MoneyRequest req) {
        BigDecimal bal = bankService.withdraw(req.getLineUserId(), req.getAmount(), req.getNote());
        Map<String, Object> resp = new HashMap<>();
        resp.put("lineUserId", req.getLineUserId());
        resp.put("newBalance", bal);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/transactions")
    public List<Transaction> transactions(@RequestParam String lineUserId) {
        return bankService.lastTransactions(lineUserId);
    }

    // 把常見錯誤轉成 422，回 LINE/前端較友善
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", ex.getMessage()));
    }
}
