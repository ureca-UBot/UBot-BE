SELECT
    s.store_id,
    s.store_name,
    s.sido,
    s.sigungu,
    s.address,
    s.phone_number,
    s.business_hours,
    s.latitude,
    s.longitude
FROM stores s
WHERE s.is_active = TRUE
  AND s.deleted_at IS NULL
  AND (
      CAST(:sido AS VARCHAR) = ''
      OR s.sido = CAST(:sido AS VARCHAR)
  )
  AND (
      CAST(:sigungu AS VARCHAR) = ''
      OR s.sigungu = CAST(:sigungu AS VARCHAR)
  )
  AND (
      :typeCount = 0
      OR s.store_id IN (
          SELECT ss.store_id
          FROM store_services ss
          JOIN service_types st
            ON st.service_type_id = ss.service_type_id
          WHERE st.service_code IN (:types)
            AND st.is_active = TRUE
          GROUP BY ss.store_id
          HAVING COUNT(DISTINCT st.service_code) = :typeCount
      )
  )
ORDER BY s.store_id
LIMIT :limit OFFSET :offset;
