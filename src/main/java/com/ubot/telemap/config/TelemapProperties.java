package com.ubot.telemap.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telemap")
public class TelemapProperties implements InitializingBean {

    private int windowMinutes = 10;
    private int countThreshold = 100;
    private double clusterThreshold = 0.6;
    private int retentionMinutes = 60;

    @Override
    public void afterPropertiesSet() {
        if (windowMinutes < 1) {
            throw new IllegalStateException("TELEMAP_WINDOW_MINUTES는 1 이상이어야 합니다: " + windowMinutes);
        }
        if (countThreshold < 1) {
            throw new IllegalStateException("TELEMAP_COUNT_THRESHOLD는 1 이상이어야 합니다: " + countThreshold);
        }
        if (clusterThreshold <= 0 || clusterThreshold > 1) {
            throw new IllegalStateException("TELEMAP_CLUSTER_THRESHOLD는 0보다 크고 1 이하여야 합니다: "
                    + clusterThreshold);
        }
        if (retentionMinutes < windowMinutes) {
            throw new IllegalStateException("TELEMAP_RETENTION_MINUTES는 TELEMAP_WINDOW_MINUTES 이상이어야 합니다: "
                    + retentionMinutes);
        }
    }

    public int getWindowMinutes() {
        return windowMinutes;
    }

    public void setWindowMinutes(int windowMinutes) {
        this.windowMinutes = windowMinutes;
    }

    public int getCountThreshold() {
        return countThreshold;
    }

    public void setCountThreshold(int countThreshold) {
        this.countThreshold = countThreshold;
    }

    public double getClusterThreshold() {
        return clusterThreshold;
    }

    public void setClusterThreshold(double clusterThreshold) {
        this.clusterThreshold = clusterThreshold;
    }

    public int getRetentionMinutes() {
        return retentionMinutes;
    }

    public void setRetentionMinutes(int retentionMinutes) {
        this.retentionMinutes = retentionMinutes;
    }
}
