package com.ubot.subscription.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ubot.subscription.entity.Subscription;

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
}
