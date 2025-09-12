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

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000000"); // 1e8，避免異常超大金額

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public BankService(AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * 註冊 / 重新啟用：
     * - 若有「啟用中的帳戶」→ 只更新名字（若不同）
     * - 若只有「已停用帳戶」→ 重新啟用並更新名字
     * - 若完全沒有 → 建立新帳戶（餘額預設 0）
     */
    @Transactional
    public Account register(String lineUserId, String name) {
        String newName = normalizeName(name);

        // 1) 先找 active=true 的帳戶
        var activeOpt = accountRepository.findByLineUserIdAndActiveTrue(lineUserId);
        if (activeOpt.isPresent()) {
            var acc = activeOpt.get();
            if (!newName.equals(acc.getName())) {
                acc.setName(newName);
                accountRepository.save(acc);
            }
            return acc;
        }

        // 2) 若有任何帳戶（可能已停用）→ 重新啟用 + 更新名稱
        var anyOpt = accountRepository.findByLineUserId(lineUserId);
        if (anyOpt.isPresent()) {
            var acc = anyOpt.get();
            boolean changed = false;
            if (!acc.isActive()) { acc.setActive(true); changed = true; }
            if (!newName.equals(acc.getName())) { acc.setName(newName); changed = true; }
            if (changed) accountRepository.save(acc);
            return acc;
        }

        // 3) 完全沒有 → 新建
        return accountRepository.save(new Account(lineUserId, newName));
    }

    /** 改名（僅允許啟用中的帳戶） */
    @Transactional
    public Account rename(String lineUserId, String newName) {
        String name = normalizeName(newName);
        var acc = getActiveAccount(lineUserId);
        if (!name.equals(acc.getName())) {
            acc.setName(name);
            accountRepository.save(acc);
        }
        return acc;
    }

    /** 查餘額（僅啟用帳戶） */
    @Transactional(readOnly = true)
    public BigDecimal getBalance(String lineUserId) {
        return getActiveAccount(lineUserId).getBalance();
    }

    /** 存款（悲觀鎖 + 僅啟用帳戶） */
    @Transactional
    public BigDecimal deposit(String lineUserId, BigDecimal amount, String note) {
        validateAmount(amount);
        var acc = getActiveAccountForUpdate(lineUserId);
        acc.setBalance(acc.getBalance().add(amount));
        transactionRepository.save(new Transaction(acc, TransactionType.DEPOSIT, amount, note));
        // JPA 變更追蹤會自動 flush，必要時可手動 save(acc)
        return acc.getBalance();
    }

    /** 提款（悲觀鎖 + 僅啟用帳戶） */
    @Transactional
    public BigDecimal withdraw(String lineUserId, BigDecimal amount, String note) {
        validateAmount(amount);
        var acc = getActiveAccountForUpdate(lineUserId);
        if (acc.getBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("餘額不足");
        }
        acc.setBalance(acc.getBalance().subtract(amount));
        transactionRepository.save(new Transaction(acc, TransactionType.WITHDRAW, amount, note));
        return acc.getBalance();
    }

    /** 最近 20 筆交易（僅啟用帳戶） */
    @Transactional(readOnly = true)
    public List<Transaction> lastTransactions(String lineUserId) {
        var acc = getActiveAccount(lineUserId);
        return transactionRepository.findTop20ByAccountOrderByCreatedAtDesc(acc);
    }

    /** 讀取帳戶（僅啟用帳戶）— 提供給其他層使用 */
    @Transactional(readOnly = true)
    public Account getAccount(String lineUserId) {
        return getActiveAccount(lineUserId);
    }

    /** 軟刪除（停用帳戶）— 啟用中才允許停用；已停用則回明確錯誤 */
    @Transactional
    public void deactivateAccount(String lineUserId) {
        var activeOpt = accountRepository.findByLineUserIdAndActiveTrue(lineUserId);
        if (activeOpt.isEmpty()) {
            // 可能本就停用或不存在
            var anyOpt = accountRepository.findByLineUserId(lineUserId);
            if (anyOpt.isPresent() && !anyOpt.get().isActive()) {
                throw new IllegalArgumentException("帳戶已停用");
            }
            throw new IllegalArgumentException("帳戶不存在或已停用");
        }
        var acc = activeOpt.get();
        acc.setActive(false);
        accountRepository.save(acc);
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
        // 避免非理性超大數值衝爆資料庫或觸發上游限制
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("金額過大，請分次操作");
        }
    }

    private String normalizeName(String name) {
        String n = (name == null ? "" : name.trim());
        return n.isBlank() ? "用戶" : n;
    }
}
