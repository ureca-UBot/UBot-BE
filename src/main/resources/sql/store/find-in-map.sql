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
  AND ST_Intersects(
      s.location,
      ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326)::geography
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
ORDER BY s.store_id;
