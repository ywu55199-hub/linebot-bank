package com.example.linebot_bank.repository;

import com.example.linebot_bank.model.Transaction;
import com.example.linebot_bank.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findTop20ByAccountOrderByCreatedAtDesc(Account account);
}
