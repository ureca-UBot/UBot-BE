package com.ubot.subscription.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ubot.subscription.entity.SubscriptionRoaming;
import com.ubot.subscription.enums.SubscriptionRoamingStatus;
import com.ubot.subscription.enums.SubscriptionStatus;

public interface SubscriptionRoamingRepository extends JpaRepository<SubscriptionRoaming, Long> {
	@EntityGraph(attributePaths = "roamingProduct")
	@Query("""
			select record from SubscriptionRoaming record
			where record.subscription.subscriptionId = :subscriptionId
			  and record.subscription.user.id = :userId
			  and record.subscription.user.deletedAt is null
			order by record.startDate desc, record.subscriptionRoamingId desc
			""")
	List<SubscriptionRoaming> findAllBySubscriptionIdAndUserId(
			@Param("subscriptionId") Long subscriptionId, @Param("userId") Long userId);

	// 로밍 시작 리마인더 대상. 알림 문구에 사용자 ID가 필요해 subscription.user를 함께 가져온다.
	@EntityGraph(attributePaths = { "subscription", "subscription.user" })
	@Query("""
			select record from SubscriptionRoaming record
			where record.startDate between :from and :to
			  and record.status in :statuses
			  and record.subscription.status = :subscriptionStatus
			  and record.subscription.user.deletedAt is null
			order by record.startDate asc, record.subscriptionRoamingId asc
			""")
	List<SubscriptionRoaming> findByStartDateBetween(
			@Param("from") LocalDate from,
			@Param("to") LocalDate to,
			@Param("statuses") Collection<SubscriptionRoamingStatus> statuses,
			@Param("subscriptionStatus") SubscriptionStatus subscriptionStatus);

	// 로밍 종료 리마인더 대상
	@EntityGraph(attributePaths = { "subscription", "subscription.user" })
	@Query("""
			select record from SubscriptionRoaming record
			where record.endDate between :from and :to
			  and record.status in :statuses
			  and record.subscription.status = :subscriptionStatus
			  and record.subscription.user.deletedAt is null
			order by record.endDate asc, record.subscriptionRoamingId asc
			""")
	List<SubscriptionRoaming> findByEndDateBetween(
			@Param("from") LocalDate from,
			@Param("to") LocalDate to,
			@Param("statuses") Collection<SubscriptionRoamingStatus> statuses,
			@Param("subscriptionStatus") SubscriptionStatus subscriptionStatus);

	@Query("""
			select record from SubscriptionRoaming record
			where record.subscriptionRoamingId = :subscriptionRoamingId
			  and record.subscription.subscriptionId = :subscriptionId
			""")
	Optional<SubscriptionRoaming> findBySubscriptionRoamingIdAndSubscriptionId(
			@Param("subscriptionRoamingId") Long subscriptionRoamingId, @Param("subscriptionId") Long subscriptionId);

	// 예약 중이거나 이용 중인 로밍 가운데 기간이 하루라도 겹치는 것이 있는지 본다. 종료일도 이용 기간에 들어간다.
	@Query("""
			select count(record) > 0 from SubscriptionRoaming record
			where record.subscription.subscriptionId = :subscriptionId
			  and record.status in (com.ubot.subscription.enums.SubscriptionRoamingStatus.SCHEDULED,
			                        com.ubot.subscription.enums.SubscriptionRoamingStatus.ACTIVE)
			  and record.startDate <= :endDate
			  and record.endDate >= :startDate
			""")
	boolean existsOverlapping(
			@Param("subscriptionId") Long subscriptionId,
			@Param("startDate") LocalDate startDate,
			@Param("endDate") LocalDate endDate);

	// 회선을 해지할 때 함께 취소할 예약·이용 중 로밍
	@Query("""
			select record from SubscriptionRoaming record
			where record.subscription.subscriptionId = :subscriptionId
			  and record.status in (com.ubot.subscription.enums.SubscriptionRoamingStatus.SCHEDULED,
			                        com.ubot.subscription.enums.SubscriptionRoamingStatus.ACTIVE)
			""")
	List<SubscriptionRoaming> findAllPendingBySubscriptionId(@Param("subscriptionId") Long subscriptionId);

	// 로밍은 하루(한국 시간 0시~24시)가 한 구간이다. 시작일이 된 예약은 이용 중으로 바꾼다.
	@Modifying
	@Query("""
			update SubscriptionRoaming record
			set record.status = com.ubot.subscription.enums.SubscriptionRoamingStatus.ACTIVE, record.updatedAt = :now
			where record.status = com.ubot.subscription.enums.SubscriptionRoamingStatus.SCHEDULED
			  and record.startDate <= :today
			  and record.endDate >= :today
			""")
	int activateStarted(@Param("today") LocalDate today, @Param("now") LocalDateTime now);

	// 종료일이 지난 예약·이용 중 로밍은 종료로 바꾼다.
	@Modifying
	@Query("""
			update SubscriptionRoaming record
			set record.status = com.ubot.subscription.enums.SubscriptionRoamingStatus.EXPIRED, record.updatedAt = :now
			where record.status in (com.ubot.subscription.enums.SubscriptionRoamingStatus.SCHEDULED,
			                        com.ubot.subscription.enums.SubscriptionRoamingStatus.ACTIVE)
			  and record.endDate < :today
			""")
	int expireEnded(@Param("today") LocalDate today, @Param("now") LocalDateTime now);
}
