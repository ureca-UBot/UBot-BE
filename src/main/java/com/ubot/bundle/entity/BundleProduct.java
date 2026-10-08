package com.ubot.bundle.entity;

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
@Table(name = "bundle_products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BundleProduct extends AdminManagedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "bundle_product_id")
    private Long bundleProductId;

    @Column(name = "code")
    private String code;

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "discount_amount")
    private Integer discountAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private MasterStatus status;

    public static BundleProduct create(
            User administrator,
            String code,
            String name,
            String description,
            Integer discountAmount
    ) {
        BundleProduct product = new BundleProduct();
        product.code = code;
        product.name = name;
        product.description = description;
        product.discountAmount = discountAmount;
        product.status = MasterStatus.ACTIVE;
        product.initializeMetadata(administrator);
        return product;
    }

    public void update(
            User administrator,
            String name,
            String description,
            Integer discountAmount
    ) {
        this.name = name;
        this.description = description;
        this.discountAmount = discountAmount;
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
