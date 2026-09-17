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
import com.ubot.store.dto.NearbyStoreResponseDto;
import com.ubot.store.dto.StoreDetailResponseDto;
import com.ubot.store.dto.StoreDetailResponseDto.ServiceResponseDto;
import com.ubot.store.dto.StoreListResponseDto;

@Repository
public class StoreRepository {

    private static final String FIND_STORES_SQL = loadSql("sql/store/find-stores.sql");
    private static final String FIND_NEARBY_SQL = loadSql("sql/store/find-nearby.sql");
    private static final String FIND_IN_MAP_SQL = loadSql("sql/store/find-in-map.sql");
    private static final String FIND_DETAIL_SQL = loadSql("sql/store/find-detail.sql");

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public StoreRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<StoreListResponseDto> findStores(String sido, String sigungu, String type) {
        Map<String, Object> parameters = Map.of(
                "sido", sido == null ? "" : sido,
                "sigungu", sigungu == null ? "" : sigungu,
                "type", type == null ? "" : type
        );

        return jdbcTemplate.query(FIND_STORES_SQL, parameters, this::mapStoreList);
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

    public List<NearbyStoreResponseDto> findNearby(
            double latitude,
            double longitude,
            double radiusMeters,
            String type,
            int limit
    ) {
        Map<String, Object> parameters = Map.of(
                "latitude", latitude,
                "longitude", longitude,
                "radiusMeters", radiusMeters,
                "type", type == null ? "" : type,
                "limit", limit
        );

        return jdbcTemplate.query(FIND_NEARBY_SQL, parameters, this::mapNearbyStore);
    }

    public List<MapStoreResponseDto> findInMap(
            double swLat,
            double swLng,
            double neLat,
            double neLng,
            String type
    ) {
        Map<String, Object> parameters = Map.of(
                "swLat", swLat,
                "swLng", swLng,
                "neLat", neLat,
                "neLng", neLng,
                "type", type == null ? "" : type
        );

        return jdbcTemplate.query(FIND_IN_MAP_SQL, parameters, this::mapStore);
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

    private static String loadSql(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("SQL 파일을 읽을 수 없습니다: " + path, exception);
        }
    }
}
