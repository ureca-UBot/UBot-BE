WITH reference_location AS (
    SELECT CASE
        WHEN :hasReference THEN ST_SetSRID(
            ST_MakePoint(:longitude, :latitude),
            4326
        )::geography
        ELSE NULL
    END AS location
),
store_with_distance AS (
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
        CASE
            WHEN r.location IS NULL THEN NULL
            ELSE ST_Distance(s.location, r.location)
        END AS distance_meters
    FROM stores s
    CROSS JOIN reference_location r
    WHERE s.is_active = TRUE
      AND s.deleted_at IS NULL
      AND ST_Intersects(
          s.location,
          ST_MakeEnvelope(
              :swLng,
              :swLat,
              :neLng,
              :neLat,
              4326
          )::geography
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
)
SELECT
    store_id,
    store_name,
    sido,
    sigungu,
    address,
    phone_number,
    business_hours,
    latitude,
    longitude,
    CASE
        WHEN distance_meters IS NULL THEN NULL
        ELSE ROUND(
            (distance_meters / 1000.0)::numeric,
            3
        )::double precision
    END AS distance_km
FROM store_with_distance
ORDER BY
    distance_meters ASC NULLS LAST,
    store_id ASC;