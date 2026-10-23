package com.ubot.subscription.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ubot.subscription.entity.BundleGroup;

import jakarta.persistence.LockModeType;

public interface BundleGroupRepository extends JpaRepository<BundleGroup, Long> {

	// 회선 추가·빼기가 동시에 들어와도 회선 수 제한을 지키도록 잠근다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@EntityGraph(attributePaths = { "representativeSubscription", "representativeSubscription.user" })
	@Query("""
			select g from BundleGroup g
			where g.bundleGroupId = :bundleGroupId
			  and g.status = com.ubot.subscription.enums.BundleGroupStatus.ACTIVE
			""")
	Optional<BundleGroup> findActiveForUpdate(@Param("bundleGroupId") Long bundleGroupId);
}
