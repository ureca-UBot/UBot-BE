package com.ubot.inquiry.repository;

import java.util.Optional;

import com.ubot.inquiry.enums.InquiryStatus;
import com.ubot.inquiry.entity.Inquiry;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {
    Page<Inquiry> findByUserId(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = "user")
    Page<Inquiry> findByStatus(InquiryStatus status, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "user")
    Page<Inquiry> findAll(Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select inquiry from Inquiry inquiry where inquiry.id = :id")
    Optional<Inquiry> findByIdForUpdate(@Param("id") Long id);
}
