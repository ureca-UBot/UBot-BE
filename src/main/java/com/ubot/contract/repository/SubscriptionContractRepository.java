package com.ubot.contract.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ubot.contract.entity.SubscriptionContract;
import com.ubot.contract.enums.ContractStatus;
import com.ubot.contract.enums.InstallmentStatus;
import com.ubot.subscription.enums.SubscriptionStatus;

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

	// 약정 만료 리마인더 대상. 알림 문구에 사용자 ID가 필요해 subscription.user를 함께 가져옴
	@EntityGraph(attributePaths = { "subscription", "subscription.user" })
	@Query("""
			select contract from SubscriptionContract contract
			where contract.expiresAt between :from and :to
			  and contract.status = :status
			  and contract.subscription.status = :subscriptionStatus
			  and contract.subscription.user.deletedAt is null
			order by contract.expiresAt asc, contract.contractId asc
			""")
	List<SubscriptionContract> findByExpiresAtBetween(
			@Param("from") LocalDate from,
			@Param("to") LocalDate to,
			@Param("status") ContractStatus status,
			@Param("subscriptionStatus") SubscriptionStatus subscriptionStatus);

	// 단말 할부 만료 리마인더 대상. 할부는 약정이 끝나도 남을 수 있어 약정 상태는 보지 않음
	@EntityGraph(attributePaths = { "subscription", "subscription.user" })
	@Query("""
			select contract from SubscriptionContract contract
			where contract.installmentExpiresAt between :from and :to
			  and contract.installmentStatus = :installmentStatus
			  and contract.subscription.status = :subscriptionStatus
			  and contract.subscription.user.deletedAt is null
			order by contract.installmentExpiresAt asc, contract.contractId asc
			""")
	List<SubscriptionContract> findByInstallmentExpiresAtBetween(
			@Param("from") LocalDate from,
			@Param("to") LocalDate to,
			@Param("installmentStatus") InstallmentStatus installmentStatus,
			@Param("subscriptionStatus") SubscriptionStatus subscriptionStatus);
}