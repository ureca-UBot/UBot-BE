package com.ubot.store.repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ubot.store.dto.MapStoreResponseDto;
import com.ubot.store.dto.MapClusterResponseDto;
import com.ubot.store.dto.NearbyStoreResponseDto;
import com.ubot.store.dto.StoreDetailResponseDto;
import com.ubot.store.dto.StoreDetailResponseDto.ServiceResponseDto;
import com.ubot.store.dto.StoreListResponseDto;

@Repository
public class StoreRepository {

    private static final String FIND_STORES_SQL = loadSql("sql/store/find-stores.sql");
    private static final String COUNT_STORES_SQL = loadSql("sql/store/count-stores.sql");
    private static final String FIND_NEARBY_SQL = loadSql("sql/store/find-nearby.sql");
    private static final String FIND_IN_MAP_SQL = loadSql("sql/store/find-in-map.sql");
    private static final String FIND_CLUSTERS_SQL = loadSql("sql/store/find-clusters.sql");
    private static final String FIND_DETAIL_SQL = loadSql("sql/store/find-detail.sql");
    private static final String FIND_SIDOS_SQL = loadSql("sql/store/find-sidos.sql");
    private static final String FIND_SIGUNGUS_SQL = loadSql("sql/store/find-sigungus.sql");
    private static final String COUNT_ACTIVE_SERVICE_TYPES_SQL = loadSql(
            "sql/store/count-active-service-types.sql"
    );

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public StoreRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long countActiveServiceTypes(List<String> types) {
        Long count = jdbcTemplate.queryForObject(
                COUNT_ACTIVE_SERVICE_TYPES_SQL,
                Map.of("types", sqlTypes(types)),
                Long.class
        );
        return count == null ? 0 : count;
    }

    public List<StoreListResponseDto> findStores(
            String sido,
            String sigungu,
            List<String> types,
            int page,
            int size
    ) {
        Map<String, Object> parameters = Map.of(
                "sido", sido == null ? "" : sido,
                "sigungu", sigungu == null ? "" : sigungu,
                "types", sqlTypes(types),
                "typeCount", types.size(),
                "limit", size,
                "offset", (long) page * size
        );

        return jdbcTemplate.query(FIND_STORES_SQL, parameters, this::mapStoreList);
    }

    public long countStores(String sido, String sigungu, List<String> types) {
        Map<String, Object> parameters = Map.of(
                "sido", sido == null ? "" : sido,
                "sigungu", sigungu == null ? "" : sigungu,
                "types", sqlTypes(types),
                "typeCount", types.size()
        );
        Long count = jdbcTemplate.queryForObject(COUNT_STORES_SQL, parameters, Long.class);
        return count == null ? 0 : count;
    }

    public Optional<StoreDetailResponseDto> findById(long storeId) {
        return jdbcTemplate.query(FIND_DETAIL_SQL, Map.of("storeId", storeId), resultSet -> {
            if (!resultSet.next()) {
                return Optional.empty();
            }

            String[] serviceCodes = (String[]) resultSet.getArray("service_codes").getArray();
            String[] serviceNames = (String[]) resultSet.getArray("service_names").getArray();
            List<ServiceResponseDto> services = IntStream.range(0, serviceCodes.length)
                    .mapToObj(index -> new ServiceResponseDto(serviceCodes[index], serviceNames[index]))
                    .toList();

            return Optional.of(new StoreDetailResponseDto(
                    resultSet.getLong("store_id"),
                    resultSet.getString("store_name"),
                    resultSet.getString("sido"),
                    resultSet.getString("sigungu"),
                    resultSet.getString("address"),
                    resultSet.getString("phone_number"),
                    resultSet.getString("business_hours"),
                    resultSet.getDouble("latitude"),
                    resultSet.getDouble("longitude"),
                    services
            ));
        });
    }

    public List<String> findSidos() {
        return jdbcTemplate.query(
                FIND_SIDOS_SQL,
                Map.of(),
                (resultSet, rowNumber) -> resultSet.getString("sido")
        );
    }

    public List<String> findSigungus(String sido) {
        return jdbcTemplate.query(
                FIND_SIGUNGUS_SQL,
                Map.of("sido", sido),
                (resultSet, rowNumber) -> resultSet.getString("sigungu")
        );
    }

    public List<NearbyStoreResponseDto> findNearby(
            double latitude,
            double longitude,
            double radiusMeters,
            List<String> types,
            int limit
    ) {
        Map<String, Object> parameters = Map.of(
                "latitude", latitude,
                "longitude", longitude,
                "radiusMeters", radiusMeters,
                "types", sqlTypes(types),
                "typeCount", types.size(),
                "limit", limit
        );

        return jdbcTemplate.query(FIND_NEARBY_SQL, parameters, this::mapNearbyStore);
    }

    public List<MapStoreResponseDto> findInMap(
            double swLat,
            double swLng,
            double neLat,
            double neLng,
            List<String> types
    ) {
        Map<String, Object> parameters = Map.of(
                "swLat", swLat,
                "swLng", swLng,
                "neLat", neLat,
                "neLng", neLng,
                "types", sqlTypes(types),
                "typeCount", types.size()
        );

        return jdbcTemplate.query(FIND_IN_MAP_SQL, parameters, this::mapStore);
    }

    public List<MapClusterResponseDto> findClusters(
            double swLat,
            double swLng,
            double neLat,
            double neLng,
            double gridMeters,
            List<String> types
    ) {
        Map<String, Object> parameters = Map.of(
                "swLat", swLat,
                "swLng", swLng,
                "neLat", neLat,
                "neLng", neLng,
                "gridMeters", gridMeters,
                "types", sqlTypes(types),
                "typeCount", types.size()
        );

        return jdbcTemplate.query(FIND_CLUSTERS_SQL, parameters, (resultSet, rowNumber) -> (
                new MapClusterResponseDto(
                        resultSet.getDouble("latitude"),
                        resultSet.getDouble("longitude"),
                        resultSet.getLong("store_count")
                )
        ));
    }

    private NearbyStoreResponseDto mapNearbyStore(ResultSet resultSet, int rowNumber) throws SQLException {
        return new NearbyStoreResponseDto(
                resultSet.getLong("store_id"),
                resultSet.getString("store_name"),
                resultSet.getString("sido"),
                resultSet.getString("sigungu"),
                resultSet.getString("address"),
                resultSet.getString("phone_number"),
                resultSet.getString("business_hours"),
                resultSet.getDouble("latitude"),
                resultSet.getDouble("longitude"),
                resultSet.getDouble("distance_km")
        );
    }

    private StoreListResponseDto mapStoreList(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoreListResponseDto(
                resultSet.getLong("store_id"),
                resultSet.getString("store_name"),
                resultSet.getString("sido"),
                resultSet.getString("sigungu"),
                resultSet.getString("address"),
                resultSet.getString("phone_number"),
                resultSet.getString("business_hours"),
                resultSet.getDouble("latitude"),
                resultSet.getDouble("longitude")
        );
    }

    private MapStoreResponseDto mapStore(ResultSet resultSet, int rowNumber) throws SQLException {
        return new MapStoreResponseDto(
                resultSet.getLong("store_id"),
                resultSet.getString("store_name"),
                resultSet.getString("sido"),
                resultSet.getString("sigungu"),
                resultSet.getString("address"),
                resultSet.getString("phone_number"),
                resultSet.getString("business_hours"),
                resultSet.getDouble("latitude"),
                resultSet.getDouble("longitude")
        );
    }

    private List<String> sqlTypes(List<String> types) {
        return types.isEmpty() ? List.of("") : types;
    }

    private static String loadSql(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("SQL 파일을 읽을 수 없습니다: " + path, exception);
        }
    }
}
