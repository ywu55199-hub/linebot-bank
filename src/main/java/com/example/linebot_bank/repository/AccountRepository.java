package com.example.linebot_bank.repository;

import com.example.linebot_bank.model.Account;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /** 依 LINE 使用者 ID 取得帳戶（不過濾啟用狀態；用於註冊時的「再次啟用」判斷） */
    Optional<Account> findByLineUserId(String lineUserId);

    // --- 建議主要使用：僅操作啟用中的帳戶（active = true） ---

    /** 讀取啟用帳戶 */
    Optional<Account> findByLineUserIdAndActiveTrue(String lineUserId);

    /** 檢查啟用帳戶是否存在 */
    boolean existsByLineUserIdAndActiveTrue(String lineUserId);

    /** 讀取啟用帳戶（悲觀鎖；用於存/提款避免併發衝突） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.lineUserId = :lineUserId and a.active = true")
    Optional<Account> findActiveByLineUserIdForUpdate(@Param("lineUserId") String lineUserId);
}
