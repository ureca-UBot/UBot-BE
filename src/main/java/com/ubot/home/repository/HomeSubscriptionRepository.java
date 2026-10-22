package com.ubot.home.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ubot.home.entity.HomeSubscription;
import com.ubot.home.enums.HomeProductType;
import com.ubot.home.enums.HomeSubscriptionStatus;

public interface HomeSubscriptionRepository extends JpaRepository<HomeSubscription, Long> {

    @EntityGraph(attributePaths = "homeProduct")
    @Query("""
            select record from HomeSubscription record
            where record.user.id = :userId and record.user.deletedAt is null
            order by record.joinedAt desc, record.homeSubscriptionId desc
            """)
    List<HomeSubscription> findAllByUserId(@Param("userId") Long userId);

    Optional<HomeSubscription> findByHomeSubscriptionIdAndUser_IdAndStatus(
            Long homeSubscriptionId, Long userId, HomeSubscriptionStatus status);

    @EntityGraph(attributePaths = "homeProduct")
    Optional<HomeSubscription> findByUser_IdAndProductTypeAndStatus(
            Long userId, HomeProductType productType, HomeSubscriptionStatus status);
}
