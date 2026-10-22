package com.ubot.subscription.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ubot.subscription.entity.Subscription;

import jakarta.persistence.LockModeType;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
	@EntityGraph(attributePaths = "plan")
	@Query("""
			select s from Subscription s
			where s.user.id = :userId and s.user.deletedAt is null
			order by s.activatedAt desc, s.subscriptionId desc
			""")
	List<Subscription> findAllByUserId(@Param("userId") Long userId);

	@EntityGraph(attributePaths = "plan")
	@Query("""
			select s from Subscription s
			where s.subscriptionId = :subscriptionId
			  and s.user.id = :userId and s.user.deletedAt is null
			""")
	Optional<Subscription> findBySubscriptionIdAndUserId(
			@Param("subscriptionId") Long subscriptionId, @Param("userId") Long userId);

	// 요금제 변경과 가입 신청이 같은 회선에서 동시에 처리되지 않도록 잠근다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select s from Subscription s
			where s.subscriptionId = :subscriptionId
			  and s.user.id = :userId and s.user.deletedAt is null
			""")
	Optional<Subscription> findBySubscriptionIdAndUserIdForUpdate(
			@Param("subscriptionId") Long subscriptionId, @Param("userId") Long userId);

	// 결합에 추가할 회선. 하이픈 유무와 관계없이 숫자만 비교한다.
	@Query(value = """
			SELECT s.* FROM subscriptions s
			JOIN users u ON u.user_id = s.user_id
			WHERE regexp_replace(s.phone_number, '[^0-9]', '', 'g') = :digits
			  AND s.status = 'ACTIVE' AND u.deleted_at IS NULL
			""", nativeQuery = true)
	Optional<Subscription> findActiveByPhoneDigits(@Param("digits") String digits);
}
