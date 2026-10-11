package com.ubot.billing.repository;

import java.time.LocalDate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
}
