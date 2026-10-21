package com.ubot.home.entity;

import com.ubot.common.entity.AdminManagedEntity;
import com.ubot.common.enums.MasterStatus;
import com.ubot.home.enums.HomeProductType;
import com.ubot.user.entity.User;

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

    public static HomeProduct create(
            User administrator,
            String code,
            String name,
            String description,
            HomeProductType productType,
            Integer speedMbps,
            Integer monthlyFee
    ) {
        HomeProduct product = new HomeProduct();
        product.code = code;
        product.name = name;
        product.description = description;
        product.productType = productType;
        product.speedMbps = speedMbps;
        product.monthlyFee = monthlyFee;
        product.status = MasterStatus.ACTIVE;
        product.initializeMetadata(administrator);
        return product;
    }

    public void update(
            User administrator,
            String name,
            String description,
            Integer speedMbps,
            Integer monthlyFee
    ) {
        this.name = name;
        this.description = description;
        this.speedMbps = speedMbps;
        this.monthlyFee = monthlyFee;
        markUpdated(administrator);
    }

    public void changeStatus(User administrator, MasterStatus status) {
        this.status = status;
        markUpdated(administrator);
    }

    public void delete(User administrator) {
        this.status = MasterStatus.INACTIVE;
        markDeleted(administrator);
    }
}
