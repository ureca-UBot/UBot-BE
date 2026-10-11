package com.ubot.contract.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ubot.contract.entity.SubscriptionContract;
import com.ubot.contract.enums.ContractStatus;

public interface SubscriptionContractRepository extends JpaRepository<SubscriptionContract, Long> {
	@Query("""
			select contract from SubscriptionContract contract
			where contract.subscription.subscriptionId = :subscriptionId
			  and contract.subscription.user.id = :userId
			  and contract.subscription.user.deletedAt is null
			order by contract.startedAt desc, contract.contractId desc
			""")
	List<SubscriptionContract> findAllBySubscriptionIdAndUserId(
			@Param("subscriptionId") Long subscriptionId, @Param("userId") Long userId);

	@Query("""
			select contract from SubscriptionContract contract
			where contract.subscription.subscriptionId = :subscriptionId
			  and contract.subscription.user.id = :userId
			  and contract.subscription.user.deletedAt is null and contract.status = :status
			""")
	Optional<SubscriptionContract> findBySubscriptionIdAndUserIdAndStatus(
			@Param("subscriptionId") Long subscriptionId, @Param("userId") Long userId,
			@Param("status") ContractStatus status);
}
