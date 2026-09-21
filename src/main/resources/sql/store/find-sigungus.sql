SELECT DISTINCT TRIM(s.sigungu) AS sigungu
FROM stores s
WHERE s.is_active = TRUE
  AND s.deleted_at IS NULL
  AND s.sido = CAST(:sido AS VARCHAR)
  AND s.sigungu IS NOT NULL
  AND TRIM(s.sigungu) <> ''
ORDER BY sigungu;
