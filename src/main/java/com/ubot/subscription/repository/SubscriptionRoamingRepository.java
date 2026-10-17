package com.ubot.subscription.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
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
}