SELECT COUNT(DISTINCT st.service_code)
FROM service_types st
WHERE st.is_active = TRUE
  AND st.service_code IN (:types);
