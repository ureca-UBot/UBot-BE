package com.ubot.home.entity;

import com.ubot.common.entity.AdminManagedEntity;
import com.ubot.common.enums.MasterStatus;
import com.ubot.home.enums.HomeProductType;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 인터넷/IPTV 상품. 인터넷만 속도(speedMbps)가 있다. */
@Entity
@DynamicUpdate
@Table(name = "home_products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HomeProduct extends AdminManagedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "home_product_id")
    private Long homeProductId;

    @Column(name = "code")
    private String code;

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type")
    private HomeProductType productType;

    @Column(name = "speed_mbps")
    private Integer speedMbps;

    @Column(name = "monthly_fee")
    private Integer monthlyFee;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private MasterStatus status;
}
