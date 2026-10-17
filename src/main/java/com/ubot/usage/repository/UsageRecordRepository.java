package com.ubot.usage.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ubot.usage.entity.UsageRecord;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, Long> {
	@Query("""
			select record from UsageRecord record
			where record.subscription.subscriptionId = :subscriptionId
			  and record.subscription.user.id = :userId
			  and record.subscription.user.deletedAt is null
			""")
	Page<UsageRecord> findAllBySubscriptionIdAndUserId(
			@Param("subscriptionId") Long subscriptionId,
			@Param("userId") Long userId,
			Pageable pageable);

	@Query("""
			select record from UsageRecord record
			where record.subscription.subscriptionId = :subscriptionId
			  and record.subscription.user.id = :userId
			  and record.subscription.user.deletedAt is null
			  and record.billingMonth = :billingMonth
			""")
	Page<UsageRecord> findAllBySubscriptionIdAndUserIdAndBillingMonth(
			@Param("subscriptionId") Long subscriptionId,
			@Param("userId") Long userId,
			@Param("billingMonth") LocalDate billingMonth,
			Pageable pageable);

	/**
	 * 데이터 사용량 리마인더 대상
	 * 이번 달 기록 중 기본 제공 데이터의 minPercent% 이상을 쓴 기록
	 * 무제한 요금제와 허용량 스냅샷이 불완전한(dataUnlimited가 false가 아니거나 dataAmountMb가 정수가 아닌) 기록은
	 * 제외
	 * 스냅샷 값의 타입이 잘못된 행이 있어도 오류가 나지 않도록 숫자일 때만 변환
	 * 허용량이 BIGINT 범위를 넘는 행은 변환 오류로 조회 전체가 실패하지 않도록 제외
	 * 사용량 비교도 NUMERIC으로 해서 곱셈이 넘치지 않게 함
	 */
	@Query(value = """
			SELECT t.usage_id AS "usageId", t.user_id AS "userId", t.data_used_mb AS "dataUsedMb",
			       CAST(t.allowance_mb AS BIGINT) AS "allowanceMb"
			FROM (
			    SELECT u.usage_id, s.user_id, u.data_used_mb,
			           CASE WHEN jsonb_typeof(u.allowance_snapshot -> 'dataAmountMb') = 'number'
			                THEN CAST(u.allowance_snapshot ->> 'dataAmountMb' AS NUMERIC)
			           END AS allowance_mb
			    FROM usage_records u
			    JOIN subscriptions s ON s.subscription_id = u.subscription_id
			    JOIN users us ON us.user_id = s.user_id
			    WHERE u.billing_month = :billingMonth
			      AND s.status = 'ACTIVE'
			      AND us.deleted_at IS NULL
			      AND (u.allowance_snapshot -> 'dataUnlimited') = CAST('false' AS JSONB)
			) t
			WHERE t.allowance_mb > 0
			  AND t.allowance_mb <= 9223372036854775807
			  AND t.allowance_mb = TRUNC(t.allowance_mb)
			  AND CAST(t.data_used_mb AS NUMERIC) * 100 >= t.allowance_mb * :minPercent
			ORDER BY t.usage_id
			""", nativeQuery = true)
	List<DataUsageTarget> findDataUsageTargets(
			@Param("billingMonth") LocalDate billingMonth,
			@Param("minPercent") int minPercent);
}