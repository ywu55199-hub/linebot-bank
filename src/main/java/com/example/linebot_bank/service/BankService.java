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

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000000"); // 上限避免異常

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public BankService(AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /** 註冊 / 重新啟用 */
    @Transactional
    public Account register(String lineUserId, String name) {
        String newName = normalizeName(name);

        // 1) 先找 active=true 的帳戶
        var activeOpt = accountRepository.findByLineUserIdAndActiveTrue(lineUserId);
        if (activeOpt.isPresent()) {
            var acc = activeOpt.get();
            if (!newName.equals(acc.getName())) {
                acc.setName(newName);
                accountRepository.saveAndFlush(acc); // 🔑 強制 flush
            }
            return acc;
        }

        // 2) 若有任何帳戶（可能已停用）→ 重新啟用 + 更新名稱
        var anyOpt = accountRepository.findByLineUserId(lineUserId);
        if (anyOpt.isPresent()) {
            var acc = anyOpt.get();
            acc.setActive(true); // 🔑 強制啟用
            acc.setName(newName);
            if (acc.getBalance() == null) {
                acc.setBalance(BigDecimal.ZERO); // 🔑 保險
            }
            accountRepository.saveAndFlush(acc);
            return acc;
        }

        // 3) 完全沒有 → 新建
        Account acc = new Account(lineUserId, newName);
        acc.setBalance(BigDecimal.ZERO);
        acc.setActive(true);
        return accountRepository.saveAndFlush(acc); // 🔑 新建後馬上 flush
    }

    /** 改名 */
    @Transactional
    public Account rename(String lineUserId, String newName) {
        String name = normalizeName(newName);
        var acc = getActiveAccount(lineUserId);
        if (!name.equals(acc.getName())) {
            acc.setName(name);
            accountRepository.saveAndFlush(acc);
        }
        return acc;
    }

    /** 查餘額 */
    @Transactional(readOnly = true)
    public BigDecimal getBalance(String lineUserId) {
        return getActiveAccount(lineUserId).getBalance();
    }

    /** 查帳戶（完整資訊） */
    @Transactional(readOnly = true)
    public Account getAccount(String lineUserId) {
        return getActiveAccount(lineUserId);
    }

    /** 存款 */
    @Transactional
    public BigDecimal deposit(String lineUserId, BigDecimal amount, String note) {
        validateAmount(amount);
        var acc = getActiveAccountForUpdate(lineUserId);
        acc.setBalance(acc.getBalance().add(amount));
        transactionRepository.save(new Transaction(acc, TransactionType.DEPOSIT, amount, note));
        accountRepository.saveAndFlush(acc); // 保險：更新餘額馬上存
        return acc.getBalance();
    }

    /** 提款 */
    @Transactional
    public BigDecimal withdraw(String lineUserId, BigDecimal amount, String note) {
        validateAmount(amount);
        var acc = getActiveAccountForUpdate(lineUserId);
        if (acc.getBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("餘額不足");
        }
        acc.setBalance(acc.getBalance().subtract(amount));
        transactionRepository.save(new Transaction(acc, TransactionType.WITHDRAW, amount, note));
        accountRepository.saveAndFlush(acc);
        return acc.getBalance();
    }

    /** 最近 20 筆交易 */
    @Transactional(readOnly = true)
    public List<Transaction> lastTransactions(String lineUserId) {
        var acc = getActiveAccount(lineUserId);
        return transactionRepository.findTop20ByAccountOrderByCreatedAtDesc(acc);
    }

    /** 停用帳戶 */
    @Transactional
    public void deactivateAccount(String lineUserId) {
        var activeOpt = accountRepository.findByLineUserIdAndActiveTrue(lineUserId);
        if (activeOpt.isEmpty()) {
            throw new IllegalArgumentException("帳戶不存在或已停用");
        }
        var acc = activeOpt.get();
        acc.setActive(false);
        accountRepository.saveAndFlush(acc);
    }

    /** 永久刪除帳戶（含交易紀錄） */
    @Transactional
    public void deleteAccount(String lineUserId) {
        var accOpt = accountRepository.findByLineUserId(lineUserId);
        if (accOpt.isEmpty()) {
            throw new IllegalArgumentException("帳戶不存在");
        }
        var acc = accOpt.get();

        // 刪交易紀錄
        transactionRepository.deleteAllByAccount(acc);

        // 刪帳戶
        accountRepository.delete(acc);
    }

    // ===== helpers =====

    private Account getActiveAccountForUpdate(String lineUserId) {
        return accountRepository.findActiveByLineUserIdForUpdate(lineUserId)
                .orElseThrow(() -> new IllegalArgumentException("帳戶不存在或已停用"));
    }

    private Account getActiveAccount(String lineUserId) {
        return accountRepository.findByLineUserIdAndActiveTrue(lineUserId)
                .orElseThrow(() -> new IllegalArgumentException("帳戶不存在或已停用"));
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("金額必須為正數");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("金額過大，請分次操作");
        }
    }

    private String normalizeName(String name) {
        String n = (name == null ? "" : name.trim());
        return n.isBlank() ? "用戶" : n;
    }
}
