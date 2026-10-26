-- 알림이 어느 클러스터에서 발생했는지 연결한다. 기존 알림과 정리 작업으로 클러스터가 지워진 알림은 NULL이다.
ALTER TABLE surging_alerts
    ADD COLUMN cluster_id BIGINT REFERENCES surging_clusters (id) ON DELETE SET NULL;

CREATE INDEX idx_surging_alerts_cluster_id
    ON surging_alerts (cluster_id)
    WHERE cluster_id IS NOT NULL;
