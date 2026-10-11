package com.ubot.product;

import java.util.Locale;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.ubot.product.ProductCrudIntegrationTest.Product;

final class ProductSubscriptionTestSupport {

    private ProductSubscriptionTestSupport() {
    }

    static History insertHistory(JdbcTemplate jdbc, Product product, long productId, long userId, String status) {
        long planId = product == Product.PLAN ? productId : jdbc.queryForObject("""
                INSERT INTO plans (plan_code, name, network_type, target_group, monthly_fee,
                    data_amount_mb, voice_minutes, sms_count)
                VALUES (?, '가입 테스트 기본 요금제', 'FIVE_G', 'GENERAL', 10000, 1000, 100, 100)
                RETURNING plan_id
                """, Long.class, UUID.randomUUID().toString());
        String subscriptionStatus = product == Product.PLAN ? status : "ACTIVE";
        long subscriptionId = jdbc.queryForObject("""
                INSERT INTO subscriptions (user_id, plan_id, phone_number, status, activated_at, terminated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP,
                    CASE WHEN ? = 'TERMINATED' THEN CURRENT_TIMESTAMP ELSE NULL END)
                RETURNING subscription_id
                """, Long.class, userId, planId, UUID.randomUUID().toString().substring(0, 30),
                subscriptionStatus, subscriptionStatus);
        switch (product) {
            case PLAN -> { }
            case BUNDLE -> jdbc.update("""
                    INSERT INTO subscription_bundles (subscription_id, bundle_product_id, joined_at,
                        ended_at, status, product_name_snapshot, applied_discount_amount)
                    SELECT ?, bundle_product_id, CURRENT_TIMESTAMP,
                        CASE WHEN ? = 'ENDED' THEN CURRENT_TIMESTAMP ELSE NULL END, ?, name, discount_amount
                    FROM bundle_products WHERE bundle_product_id = ?
                    """, subscriptionId, status, status, productId);
            case ADDON -> jdbc.update("""
                    INSERT INTO subscription_addons (subscription_id, addon_service_id, subscribed_at,
                        canceled_at, status, service_name_snapshot, monthly_fee_snapshot)
                    SELECT ?, addon_service_id, CURRENT_TIMESTAMP,
                        CASE WHEN ? = 'CANCELED' THEN CURRENT_TIMESTAMP ELSE NULL END, ?, name, monthly_fee
                    FROM addon_services WHERE addon_service_id = ?
                    """, subscriptionId, status, status, productId);
            case ROAMING -> jdbc.update("""
                    INSERT INTO subscription_roamings (subscription_id, roaming_product_id, start_date,
                        end_date, status, product_name_snapshot, daily_fee_snapshot, data_amount_mb_snapshot, canceled_at)
                    SELECT ?, roaming_product_id, CURRENT_DATE, CURRENT_DATE, ?, name, daily_fee, data_amount_mb,
                        CASE WHEN ? = 'CANCELED' THEN CURRENT_TIMESTAMP ELSE NULL END
                    FROM roaming_products WHERE roaming_product_id = ?
                    """, subscriptionId, status, status, productId);
        }
        return new History(subscriptionId, planId);
    }

    static void deleteHistory(JdbcTemplate jdbc, Product product, History history) {
        if (product != Product.PLAN) {
            String table = switch (product) {
                case BUNDLE -> "subscription_bundles";
                case ADDON -> "subscription_addons";
                case ROAMING -> "subscription_roamings";
                default -> throw new IllegalArgumentException();
            };
            jdbc.update("DELETE FROM " + table + " WHERE subscription_id = ?", history.subscriptionId());
        }
        jdbc.update("DELETE FROM subscriptions WHERE subscription_id = ?", history.subscriptionId());
        if (product != Product.PLAN) {
            jdbc.update("DELETE FROM plans WHERE plan_id = ?", history.planId());
        }
    }

    static String primaryKey(Product product) {
        return product.idField.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT);
    }

    static String unchangedUpdateBody(Product product) {
        return product.createBody.replaceFirst("\"(?:planCode|code)\":\"[^\"]+\",", "")
                .replace("  상품  ", "상품");
    }

    record History(long subscriptionId, long planId) {
    }
}
