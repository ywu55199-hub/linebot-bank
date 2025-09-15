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
import java.util.Optional;

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

        Optional<Account> existingAccountOpt = accountRepository.findByLineUserId(lineUserId);

        if (existingAccountOpt.isPresent()) {
            Account acc = existingAccountOpt.get();
            acc.setActive(true);
            acc.setName(newName);
            if (acc.getBalance() == null) {
                acc.setBalance(BigDecimal.ZERO);
            }
            return accountRepository.saveAndFlush(acc);
        } else {
            Account newAccount = new Account(lineUserId, newName);
            newAccount.setBalance(BigDecimal.ZERO);
            newAccount.setActive(true);
            return accountRepository.saveAndFlush(newAccount);
        }
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

    /** 存款 - 加入除錯日誌 */
    @Transactional
    public BigDecimal deposit(String lineUserId, BigDecimal amount, String note) {
        System.out.println("=== 開始存款流程 ===");
        System.out.println("lineUserId: " + lineUserId);
        System.out.println("amount: " + amount);
        System.out.println("note: " + note);
        
        validateAmount(amount);
        
        // 先確認帳戶存在，如果不存在則自動註冊
        Account acc;
        try {
            acc = getActiveAccountForUpdate(lineUserId);
            System.out.println("找到現有帳戶，ID: " + acc.getId());
        } catch (IllegalArgumentException e) {
            System.out.println("帳戶不存在，開始自動註冊");
            // 帳戶不存在，自動註冊一個預設帳戶
            acc = register(lineUserId, "用戶");
            System.out.println("註冊完成，帳戶 ID: " + acc.getId());
            
            // 重新取得帳戶以確保有正確的 ID
            acc = getActiveAccountForUpdate(lineUserId);
            System.out.println("重新取得帳戶，ID: " + acc.getId());
        }
        
        // 確認帳戶 ID 不為 null
        if (acc.getId() == null) {
            System.out.println("ERROR: 帳戶 ID 為 null");
            throw new IllegalStateException("帳戶 ID 為空，無法進行交易");
        }
        
        System.out.println("更新前餘額: " + acc.getBalance());
        acc.setBalance(acc.getBalance().add(amount));
        System.out.println("更新後餘額: " + acc.getBalance());
        
        // 先保存並刷新帳戶，確保 ID 正確設置
        acc = accountRepository.saveAndFlush(acc);
        System.out.println("保存帳戶後，ID: " + acc.getId());
        
        // 再次確認 ID
        if (acc.getId() == null) {
            System.out.println("ERROR: 保存後帳戶 ID 仍為 null");
            throw new IllegalStateException("保存帳戶後 ID 仍為空");
        }
        
        // 創建交易記錄
        System.out.println("準備創建交易記錄...");
        System.out.println("Account ID: " + acc.getId());
        System.out.println("Transaction Type: " + TransactionType.DEPOSIT);
        System.out.println("Amount: " + amount);
        System.out.println("Note: " + note);
        
        Transaction transaction = new Transaction(acc, TransactionType.DEPOSIT, amount, note);
        System.out.println("Transaction 建立完成，account: " + transaction.getAccount());
        System.out.println("Transaction account ID: " + transaction.getAccount().getId());
        
        try {
            transactionRepository.save(transaction);
            System.out.println("交易記錄保存成功");
        } catch (Exception e) {
            System.out.println("ERROR: 保存交易記錄時發生錯誤: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
        
        System.out.println("=== 存款流程完成 ===");
        return acc.getBalance();
    }

    /** 提款 */
    @Transactional
    public BigDecimal withdraw(String lineUserId, BigDecimal amount, String note) {
        validateAmount(amount);
        
        // 確認帳戶存在
        Account acc;
        try {
            acc = getActiveAccountForUpdate(lineUserId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("請先註冊帳戶才能進行提款");
        }
        
        // 確認帳戶 ID 不為 null
        if (acc.getId() == null) {
            throw new IllegalStateException("帳戶 ID 為空，無法進行交易");
        }
        
        if (acc.getBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("餘額不足");
        }
        
        acc.setBalance(acc.getBalance().subtract(amount));
        
        // 先保存並刷新帳戶
        acc = accountRepository.saveAndFlush(acc);
        
        // 再次確認 ID
        if (acc.getId() == null) {
            throw new IllegalStateException("保存帳戶後 ID 仍為空");
        }
        
        // 創建交易記錄
        Transaction transaction = new Transaction(acc, TransactionType.WITHDRAW, amount, note);
        transactionRepository.save(transaction);

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
        var acc = getActiveAccount(lineUserId);
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

        transactionRepository.deleteAllByAccount(acc);
        transactionRepository.flush(); 

        accountRepository.delete(acc);
        accountRepository.flush(); 
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