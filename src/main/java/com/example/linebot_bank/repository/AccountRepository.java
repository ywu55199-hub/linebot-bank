package com.example.linebot_bank.repository;

import com.example.linebot_bank.model.Account;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByLineUserId(String lineUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.lineUserId = :lineUserId")
    Optional<Account> findByLineUserIdForUpdate(@Param("lineUserId") String lineUserId);
}
