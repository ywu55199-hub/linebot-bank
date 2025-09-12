package com.example.linebot_bank.repository;

import com.example.linebot_bank.model.Transaction;
import com.example.linebot_bank.model.Account;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /** 既有使用：取最近 20 筆交易（由新到舊） */
    List<Transaction> findTop20ByAccountOrderByCreatedAtDesc(Account account);

    /** 可彈性調整回傳筆數：搭配 Pageable.ofSize(n) 使用（由新到舊） */
    List<Transaction> findByAccountOrderByCreatedAtDesc(Account account, Pageable pageable);

    /** 取最新一筆交易（由新到舊的第一筆） */
    Transaction findFirstByAccountOrderByCreatedAtDesc(Account account);

    /** 某帳戶的交易總筆數 */
    long countByAccount(Account account);
}
