package com.ubot.roaming.entity;

import com.ubot.common.entity.AdminManagedEntity;
import com.ubot.common.enums.MasterStatus;
import com.ubot.user.entity.User;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@DynamicUpdate
@Table(name = "roaming_products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoamingProduct extends AdminManagedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "roaming_product_id")
    private Long roamingProductId;

    @Column(name = "code")
    private String code;

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "daily_fee")
    private Integer dailyFee;

    @Column(name = "data_amount_mb")
    private Long dataAmountMb;

    @Column(name = "country")
    private String country;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private MasterStatus status;

    public static RoamingProduct create(
            User administrator,
            String code,
            String name,
            String description,
            Integer dailyFee,
            Long dataAmountMb,
            String country
    ) {
        RoamingProduct product = new RoamingProduct();
        product.code = code;
        product.name = name;
        product.description = description;
        product.dailyFee = dailyFee;
        product.dataAmountMb = dataAmountMb;
        product.country = country;
        product.status = MasterStatus.ACTIVE;
        product.initializeMetadata(administrator);
        return product;
    }

    public void update(
            User administrator,
            String name,
            String description,
            Integer dailyFee,
            Long dataAmountMb,
            String country
    ) {
        this.name = name;
        this.description = description;
        this.dailyFee = dailyFee;
        this.dataAmountMb = dataAmountMb;
        this.country = country;
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
