SELECT region.sido
FROM (
    SELECT DISTINCT TRIM(s.sido) AS sido
    FROM stores s
    WHERE s.is_active = TRUE
      AND s.deleted_at IS NULL
      AND s.sido IS NOT NULL
      AND TRIM(s.sido) <> ''
) region
ORDER BY CASE region.sido
    WHEN '서울특별시' THEN 1
    WHEN '경기도' THEN 2
    WHEN '인천광역시' THEN 3
    WHEN '부산광역시' THEN 4
    WHEN '대구광역시' THEN 5
    WHEN '광주광역시' THEN 6
    WHEN '대전광역시' THEN 7
    WHEN '울산광역시' THEN 8
    WHEN '세종특별자치시' THEN 9
    WHEN '강원특별자치도' THEN 10
    WHEN '충청북도' THEN 11
    WHEN '충청남도' THEN 12
    WHEN '전북특별자치도' THEN 13
    WHEN '전라남도' THEN 14
    WHEN '경상북도' THEN 15
    WHEN '경상남도' THEN 16
    WHEN '제주특별자치도' THEN 17
    ELSE 99
END,
region.sido;
