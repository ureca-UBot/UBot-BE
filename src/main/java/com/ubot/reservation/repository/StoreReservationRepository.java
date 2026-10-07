package com.ubot.reservation.repository;

import com.ubot.reservation.entity.StoreReservation;
import com.ubot.reservation.enums.ReservationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StoreReservationRepository
		extends JpaRepository<StoreReservation, Long>, JpaSpecificationExecutor<StoreReservation> {
	@Query("""
			select r.visitAt
			from StoreReservation r
			where r.store.storeId = :storeId
			  and r.status = :status
			  and r.visitAt >= :from
			  and r.visitAt < :to
			""")
	List<LocalDateTime> findVisitAtList(
			@Param("storeId") Long storeId,
			@Param("status") ReservationStatus status,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to
	);

	boolean existsByStore_StoreIdAndVisitAtAndStatus(Long storeId, LocalDateTime visitAt, ReservationStatus status);

	boolean existsByUserIdAndVisitAtAndStatus(Long userId, LocalDateTime visitAt, ReservationStatus status);

	long countByUserIdAndStatusAndVisitAtAfter(Long userId, ReservationStatus status, LocalDateTime visitAt);

	@EntityGraph(attributePaths = "store")
	List<StoreReservation> findAllByUserIdOrderByVisitAtDesc(Long userId);

	@EntityGraph(attributePaths = "store")
	Optional<StoreReservation> findByReservationIdAndUserId(Long reservationId, Long userId);

	@Query(value = "SELECT user_id FROM users WHERE user_id = :userId FOR UPDATE", nativeQuery = true)
	Long lockUser(@Param("userId") Long userId);

	@Override
	@EntityGraph(attributePaths = "store")
	Page<StoreReservation> findAll(Specification<StoreReservation> spec, Pageable pageable);
}
