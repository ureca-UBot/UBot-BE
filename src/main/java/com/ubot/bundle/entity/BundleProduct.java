package com.ubot.bundle.entity;

import com.ubot.common.entity.AdminManagedEntity;
import com.ubot.bundle.enums.BundleType;
import com.ubot.common.enums.MasterStatus;
import com.ubot.home.entity.HomeProduct;
import com.ubot.user.entity.User;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.EnumType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
    @Column(name = "bundle_type")
    private BundleType bundleType;

    // 결합에 묶는 회선 수. null이면 회선 수 제한이 없다.
    @Column(name = "line_count")
    private Integer lineCount;

    // 홈 결합에 필요한 인터넷 상품
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_product_id")
    private HomeProduct homeProduct;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private MasterStatus status;

    public static BundleProduct create(
            User administrator,
            String code,
            String name,
            String description,
            Integer discountAmount,
            BundleType bundleType,
            Integer lineCount,
            HomeProduct homeProduct
    ) {
        BundleProduct product = new BundleProduct();
        product.code = code;
        product.name = name;
        product.description = description;
        product.discountAmount = discountAmount;
        product.bundleType = bundleType;
        product.lineCount = lineCount;
        product.homeProduct = homeProduct;
        product.status = MasterStatus.ACTIVE;
        product.initializeMetadata(administrator);
        return product;
    }

    public void update(
            User administrator,
            String name,
            String description,
            Integer discountAmount,
            BundleType bundleType,
            Integer lineCount,
            HomeProduct homeProduct
    ) {
        this.name = name;
        this.description = description;
        this.discountAmount = discountAmount;
        this.bundleType = bundleType;
        this.lineCount = lineCount;
        this.homeProduct = homeProduct;
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
