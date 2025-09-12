package com.example.linebot_bank.controller;

import com.example.linebot_bank.model.Account;
import com.example.linebot_bank.model.Transaction;
import com.example.linebot_bank.service.BankService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/bank")
public class BankController {

    private final BankService bankService;

    public BankController(BankService bankService) {
        this.bankService = bankService;
    }

    /** 註冊帳戶 */
    @PostMapping("/register")
    public ResponseEntity<Account> register(
            @RequestParam String lineUserId,
            @RequestParam String name) {
        Account acc = bankService.register(lineUserId, name);
        return ResponseEntity.ok(acc);
    }

    /** 改名 */
    @PostMapping("/rename")
    public ResponseEntity<Account> rename(
            @RequestParam String lineUserId,
            @RequestParam String newName) {
        Account acc = bankService.rename(lineUserId, newName);
        return ResponseEntity.ok(acc);
    }

    /** 查餘額 */
    @GetMapping("/balance/{lineUserId}")
    public ResponseEntity<BigDecimal> getBalance(@PathVariable String lineUserId) {
        return ResponseEntity.ok(bankService.getBalance(lineUserId));
    }

    /** 查帳戶資訊 */
    @GetMapping("/account/{lineUserId}")
    public ResponseEntity<Account> getAccount(@PathVariable String lineUserId) {
        return ResponseEntity.ok(bankService.getAccount(lineUserId));
    }

    /** 存款 */
    @PostMapping("/deposit")
    public ResponseEntity<BigDecimal> deposit(
            @RequestParam String lineUserId,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) String note) {
        return ResponseEntity.ok(bankService.deposit(lineUserId, amount, note));
    }

    /** 提款 */
    @PostMapping("/withdraw")
    public ResponseEntity<BigDecimal> withdraw(
            @RequestParam String lineUserId,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) String note) {
        return ResponseEntity.ok(bankService.withdraw(lineUserId, amount, note));
    }

    /** 最近 20 筆交易 */
    @GetMapping("/transactions/{lineUserId}")
    public ResponseEntity<List<Transaction>> lastTransactions(@PathVariable String lineUserId) {
        return ResponseEntity.ok(bankService.lastTransactions(lineUserId));
    }

    /** 停用帳戶（軟刪除） */
    @PostMapping("/deactivate/{lineUserId}")
    public ResponseEntity<String> deactivate(@PathVariable String lineUserId) {
        bankService.deactivateAccount(lineUserId);
        return ResponseEntity.ok("帳戶已停用");
    }

    /** 永久刪除帳戶（含交易紀錄） */
    @DeleteMapping("/delete/{lineUserId}")
    public ResponseEntity<String> deleteAccount(@PathVariable String lineUserId) {
        bankService.deleteAccount(lineUserId);
        return ResponseEntity.ok("帳戶已刪除（含交易紀錄）");
    }
}
