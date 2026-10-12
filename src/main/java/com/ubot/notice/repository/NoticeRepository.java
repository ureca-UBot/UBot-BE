package com.ubot.notice.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ubot.notice.entity.Notice;

import jakarta.persistence.LockModeType;

public interface NoticeRepository extends JpaRepository<Notice, Long>, JpaSpecificationExecutor<Notice> {
    Optional<Notice> findByNoticeIdAndDeletedAtIsNull(Long noticeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select notice from Notice notice where notice.noticeId = :noticeId and notice.deletedAt is null")
    Optional<Notice> findByIdForUpdate(@Param("noticeId") Long noticeId);

}
