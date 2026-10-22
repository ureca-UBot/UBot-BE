package com.ubot.home.entity;

import java.time.LocalDateTime;

import com.ubot.common.entity.BusinessEntity;
import com.ubot.home.enums.HomeProductType;
import com.ubot.home.enums.HomeSubscriptionStatus;
import com.ubot.user.entity.User;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 회원이 이용하는 인터넷/IPTV. 종류(productType)마다 이용 중인 가입은 하나다. */
@Entity
@DynamicUpdate
@Table(name = "home_subscriptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HomeSubscription extends BusinessEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "home_subscription_id")
    private Long homeSubscriptionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_product_id")
    private HomeProduct homeProduct;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type")
    private HomeProductType productType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private HomeSubscriptionStatus status;

    @Column(name = "joined_at")
    private LocalDateTime joinedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "product_name_snapshot")
    private String productNameSnapshot;

    @Column(name = "monthly_fee_snapshot")
    private Integer monthlyFeeSnapshot;

    public static HomeSubscription subscribe(User user, HomeProduct homeProduct, LocalDateTime now) {
        HomeSubscription subscription = new HomeSubscription();
        subscription.user = user;
        subscription.homeProduct = homeProduct;
        subscription.productType = homeProduct.getProductType();
        subscription.status = HomeSubscriptionStatus.ACTIVE;
        subscription.joinedAt = now;
        subscription.productNameSnapshot = homeProduct.getName();
        subscription.monthlyFeeSnapshot = homeProduct.getMonthlyFee();
        return subscription;
    }

    public void end(LocalDateTime now) {
        this.status = HomeSubscriptionStatus.ENDED;
        this.endedAt = now;
    }

    @PrePersist
    private void initializeMetadata() {
        initializeTimestamps();
    }

    @PreUpdate
    private void updateTimestamp() {
        markUpdated();
    }
}
