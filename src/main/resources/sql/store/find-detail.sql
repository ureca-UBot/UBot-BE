SELECT
    s.store_id,
    s.store_name,
    s.sido,
    s.sigungu,
    s.address,
    s.phone_number,
    s.business_hours,
    s.latitude,
    s.longitude,
    COALESCE(
        ARRAY_AGG(st.service_code ORDER BY st.service_code)
            FILTER (WHERE st.service_type_id IS NOT NULL AND st.is_active = TRUE),
        ARRAY[]::VARCHAR[]
    ) AS service_codes,
    COALESCE(
        ARRAY_AGG(st.service_name ORDER BY st.service_code)
            FILTER (WHERE st.service_type_id IS NOT NULL AND st.is_active = TRUE),
        ARRAY[]::VARCHAR[]
    ) AS service_names
FROM stores s
LEFT JOIN store_services ss
  ON ss.store_id = s.store_id
LEFT JOIN service_types st
  ON st.service_type_id = ss.service_type_id
WHERE s.store_id = :storeId
  AND s.is_active = TRUE
  AND s.deleted_at IS NULL
GROUP BY s.store_id;
