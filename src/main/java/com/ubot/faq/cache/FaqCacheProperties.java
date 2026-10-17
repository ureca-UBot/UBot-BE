package com.ubot.faq.cache;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 공식 FAQ 답변을 직접 반환하는 설정입니다. 임계값은 데이터셋 검증 후 조정합니다. */
@ConfigurationProperties(prefix = "faq.cache")
public class FaqCacheProperties implements InitializingBean {

    private boolean enabled = false;
    private double minSimilarity = 0.95;
    private double minMargin = 0.15;

    @Override
    public void afterPropertiesSet() {
        if (!Double.isFinite(minSimilarity) || minSimilarity < 0 || minSimilarity > 1) {
            throw new IllegalStateException("FAQ_CACHE_MIN_SIMILARITY는 0 이상 1 이하여야 합니다.");
        }
        if (!Double.isFinite(minMargin) || minMargin <= 0 || minMargin > 2) {
            throw new IllegalStateException("FAQ_CACHE_MIN_MARGIN은 0보다 크고 2 이하여야 합니다.");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getMinSimilarity() {
        return minSimilarity;
    }

    public void setMinSimilarity(double minSimilarity) {
        this.minSimilarity = minSimilarity;
    }

    public double getMinMargin() {
        return minMargin;
    }

    public void setMinMargin(double minMargin) {
        this.minMargin = minMargin;
    }
}
