package com.ubot.billing.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ubot.billing.entity.BillingRecord;

public interface BillingRecordRepository extends JpaRepository<BillingRecord, Long> {
	@Query("""
			select record from BillingRecord record
			where record.subscription.subscriptionId = :subscriptionId
			  and record.subscription.user.id = :userId
			  and record.subscription.user.deletedAt is null
			""")
	Page<BillingRecord> findAllBySubscriptionIdAndUserId(
			@Param("subscriptionId") Long subscriptionId,
			@Param("userId") Long userId,
			Pageable pageable);

	@Query("""
			select record from BillingRecord record
			where record.subscription.subscriptionId = :subscriptionId
			  and record.subscription.user.id = :userId
			  and record.subscription.user.deletedAt is null
			  and record.billingMonth = :billingMonth
			""")
	Page<BillingRecord> findAllBySubscriptionIdAndUserIdAndBillingMonth(
			@Param("subscriptionId") Long subscriptionId,
			@Param("userId") Long userId,
			@Param("billingMonth") LocalDate billingMonth,
			Pageable pageable);

	// 납부 리마인더 대상. 낼 금액이 남아 있고 납부 기한이 [from, to]인 청구. 탈퇴한 사용자의 청구는 제외한다.
	// 알림을 받을 사용자 ID가 필요하므로 subscription과 user를 함께 가져온다.
	@EntityGraph(attributePaths = { "subscription", "subscription.user" })
	@Query("""
			select record from BillingRecord record
			where record.dueDate between :from and :to
			  and record.totalAmount > record.paidAmount
			  and record.subscription.user.deletedAt is null
			order by record.dueDate asc, record.billingId asc
			""")
	List<BillingRecord> findUnpaidByDueDateBetween(
			@Param("from") LocalDate from,
			@Param("to") LocalDate to);
}