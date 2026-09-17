SELECT EXISTS (
    SELECT 1
    FROM service_types
    WHERE service_code = :type
      AND is_active = TRUE
);
