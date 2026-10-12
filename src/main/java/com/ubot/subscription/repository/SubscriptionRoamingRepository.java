package com.ubot.subscription.repository;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ubot.subscription.entity.SubscriptionRoaming;

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
}
