package com.ubot.home.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ubot.home.entity.HomeSubscription;
import com.ubot.home.enums.HomeProductType;

public interface HomeSubscriptionRepository extends JpaRepository<HomeSubscription, Long> {

    @EntityGraph(attributePaths = "homeProduct")
    @Query("""
            select record from HomeSubscription record
            where record.user.id = :userId and record.user.deletedAt is null
            order by record.joinedAt desc, record.homeSubscriptionId desc
            """)
    List<HomeSubscription> findAllByUserId(@Param("userId") Long userId);

    @Query("""
            select record from HomeSubscription record
            where record.homeSubscriptionId = :homeSubscriptionId
              and record.user.id = :userId
              and record.status = com.ubot.home.enums.HomeSubscriptionStatus.ACTIVE
            """)
    Optional<HomeSubscription> findActiveByHomeSubscriptionIdAndUserId(
            @Param("homeSubscriptionId") Long homeSubscriptionId, @Param("userId") Long userId);

    @EntityGraph(attributePaths = "homeProduct")
    @Query("""
            select record from HomeSubscription record
            where record.user.id = :userId
              and record.productType = :productType
              and record.status = com.ubot.home.enums.HomeSubscriptionStatus.ACTIVE
            """)
    Optional<HomeSubscription> findActiveByUserIdAndProductType(
            @Param("userId") Long userId, @Param("productType") HomeProductType productType);
}
