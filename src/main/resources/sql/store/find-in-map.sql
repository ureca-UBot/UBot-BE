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
      CAST(:type AS VARCHAR) = ''
      OR EXISTS (
          SELECT 1
          FROM store_services ss
          JOIN service_types st
            ON st.service_type_id = ss.service_type_id
          WHERE ss.store_id = s.store_id
            AND st.service_code = CAST(:type AS VARCHAR)
            AND st.is_active = TRUE
      )
  )
ORDER BY s.store_id;
