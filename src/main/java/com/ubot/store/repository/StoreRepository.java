package com.ubot.store.repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
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

    /** 현재 위치({@code originLatitude}, {@code originLongitude})가 있으면 매장별 직선거리를 함께 조회합니다. */
    public List<StoreListResponseDto> findStores(
            String sido,
            String sigungu,
            List<String> types,
            Double originLatitude,
            Double originLongitude,
            int page,
            int size
    ) {
        MapSqlParameterSource parameters = originParameters(originLatitude, originLongitude)
                .addValue("sido", sido == null ? "" : sido)
                .addValue("sigungu", sigungu == null ? "" : sigungu)
                .addValue("types", sqlTypes(types))
                .addValue("typeCount", types.size())
                .addValue("limit", size)
                .addValue("offset", (long) page * size);

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
        return findById(storeId, null, null);
    }

    /** 현재 위치({@code originLatitude}, {@code originLongitude})가 있으면 매장까지의 직선거리를 함께 조회합니다. */
    public Optional<StoreDetailResponseDto> findById(
            long storeId,
            Double originLatitude,
            Double originLongitude
    ) {
        MapSqlParameterSource parameters = originParameters(originLatitude, originLongitude)
                .addValue("storeId", storeId);

        return jdbcTemplate.query(FIND_DETAIL_SQL, parameters, resultSet -> {
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
                    resultSet.getObject("distance_km", Double.class),
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
                resultSet.getDouble("longitude"),
                resultSet.getObject("distance_km", Double.class)
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

    /** 현재 위치가 없으면 hasOrigin을 false로 두고 좌표는 타입이 있는 NULL로 전달합니다. */
    private MapSqlParameterSource originParameters(Double originLatitude, Double originLongitude) {
        return new MapSqlParameterSource()
                .addValue("hasOrigin", originLatitude != null && originLongitude != null)
                .addValue("originLatitude", originLatitude, Types.DOUBLE)
                .addValue("originLongitude", originLongitude, Types.DOUBLE);
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
