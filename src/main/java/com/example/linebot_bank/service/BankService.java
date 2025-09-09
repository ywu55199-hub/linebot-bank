package com.example.linebot_bank.service;

import com.example.linebot_bank.model.Account;
import com.example.linebot_bank.model.Transaction;
import com.example.linebot_bank.model.TransactionType;
import com.example.linebot_bank.repository.AccountRepository;
import com.example.linebot_bank.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class BankService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public BankService(AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * 註冊：
     * - 若帳戶不存在 → 建立新帳戶（名稱預設為「用戶」，或使用傳入的 name）
     * - 若帳戶已存在 → 覆寫名稱（若不同），並回傳既有帳戶
     */
    @Transactional
    public Account register(String lineUserId, String name) {
        String newName = (name == null || name.isBlank()) ? "用戶" : name.trim();

        return accountRepository.findByLineUserId(lineUserId)
                .map(acc -> {
                    if (!newName.equals(acc.getName())) {
                        acc.setName(newName);
                        accountRepository.save(acc);
                    }
                    return acc;
                })
                .orElseGet(() -> accountRepository.save(new Account(lineUserId, newName)));
    }

    /** 改名（已註冊者才能改） */
    @Transactional
    public Account rename(String lineUserId, String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("名稱不可空白");
        }
        var acc = accountRepository.findByLineUserId(lineUserId)
                .orElseThrow(() -> new IllegalArgumentException("尚未註冊，請先輸入：註冊 你的名字"));
        acc.setName(newName.trim());
        return accountRepository.save(acc);
    }

    /** 查餘額 */
    @Transactional(readOnly = true)
    public BigDecimal getBalance(String lineUserId) {
        var acc = accountRepository.findByLineUserId(lineUserId)
                .orElseThrow(() -> new IllegalArgumentException("帳戶不存在"));
        return acc.getBalance();
    }

    /** 存款 */
    @Transactional
    public BigDecimal deposit(String lineUserId, BigDecimal amount, String note) {
        validateAmount(amount);
        var acc = accountRepository.findByLineUserIdForUpdate(lineUserId)
                .orElseThrow(() -> new IllegalArgumentException("帳戶不存在"));
        acc.setBalance(acc.getBalance().add(amount));
        transactionRepository.save(new Transaction(acc, TransactionType.DEPOSIT, amount, note));
        return acc.getBalance();
    }

    /** 提款 */
    @Transactional
    public BigDecimal withdraw(String lineUserId, BigDecimal amount, String note) {
        validateAmount(amount);
        var acc = accountRepository.findByLineUserIdForUpdate(lineUserId)
                .orElseThrow(() -> new IllegalArgumentException("帳戶不存在"));
        if (acc.getBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("餘額不足");
        }
        acc.setBalance(acc.getBalance().subtract(amount));
        transactionRepository.save(new Transaction(acc, TransactionType.WITHDRAW, amount, note));
        return acc.getBalance();
    }

    /** 最近 20 筆交易 */
    @Transactional(readOnly = true)
    public List<Transaction> lastTransactions(String lineUserId) {
        var acc = accountRepository.findByLineUserId(lineUserId)
                .orElseThrow(() -> new IllegalArgumentException("帳戶不存在"));
        return transactionRepository.findTop20ByAccountOrderByCreatedAtDesc(acc);
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("金額必須為正數");
        }
    }
}
