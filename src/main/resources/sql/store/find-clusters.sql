WITH cluster_source AS (
    SELECT
        ST_SnapToGrid(
            ST_Transform(s.location::geometry, 3857),
            :gridMeters
        ) AS grid_cell,
        ST_Transform(s.location::geometry, 3857) AS store_point
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
), clusters AS (
    SELECT
        grid_cell,
        ST_Centroid(ST_Collect(store_point)) AS center_point,
        COUNT(*) AS store_count
    FROM cluster_source
    GROUP BY grid_cell
)
SELECT
    ST_Y(ST_Transform(center_point, 4326)) AS latitude,
    ST_X(ST_Transform(center_point, 4326)) AS longitude,
    store_count
FROM clusters
ORDER BY store_count DESC, latitude, longitude;
