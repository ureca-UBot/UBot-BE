package com.ubot.direction.client;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.ubot.common.ErrorCode;
import com.ubot.direction.dto.DirectionsMode;
import com.ubot.direction.dto.DirectionsResponseDto;
import com.ubot.direction.dto.DirectionsResponseDto.CarFare;
import com.ubot.direction.dto.DirectionsResponseDto.CarGuideDto;
import com.ubot.direction.dto.DirectionsResponseDto.CarInfo;
import com.ubot.direction.dto.DirectionsResponseDto.PointDto;
import com.ubot.direction.dto.DirectionsResponseDto.StepDto;
import com.ubot.direction.dto.DirectionsResponseDto.TransitFare;
import com.ubot.direction.dto.DirectionsResponseDto.TransitInfo;
import com.ubot.direction.exception.DirectionsException;

/**
 * 카카오 길찾기 API를 호출하고 응답을 {@link DirectionsResponseDto}로 통일합니다.
 *
 * <ul>
 *   <li>도보, 대중교통: 카카오맵 REST API</li>
 *   <li>자동차: 카카오모빌리티 길찾기 API</li>
 * </ul>
 *
 * 카카오 정책상 대중교통 응답은 저장·재사용할 수 없으므로, 이 클래스의 결과는 캐싱하지 않고 매번 실시간으로 호출합니다.
 */
@Component
public class KakaoDirectionsClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoDirectionsClient.class);

    private static final String MAP_HOST = "dapi.kakao.com";
    private static final String NAVI_HOST = "apis-navi.kakaomobility.com";
    private static final String STATUS_OK = "OK";
    private static final int CAR_RESULT_SUCCESS = 0;

    private final RestClient restClient;

    // 카카오맵 REST API 키(kakao.local.api-key)를 그대로 사용합니다. 자동차 길찾기도 같은 키로 호출됩니다.
    @Autowired
    public KakaoDirectionsClient(
            RestClient.Builder restClientBuilder,
            @Value("${kakao.local.api-key}") String apiKey,
            @Value("${kakao.directions.connect-timeout:3s}") Duration connectTimeout,
            @Value("${kakao.directions.read-timeout:5s}") Duration readTimeout
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        this.restClient = restClientBuilder
                .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + apiKey)
                .requestFactory(requestFactory)
                .build();
    }

    KakaoDirectionsClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public DirectionsResponseDto walk(PointDto origin, PointDto destination) {
        WalkResponse response = fetch(MAP_HOST, "/v2/routing/walk", mapParams(origin, destination), WalkResponse.class);

        if (!STATUS_OK.equals(response.status()) || response.route() == null) {
            throw routeNotFound(DirectionsMode.WALK, response.status());
        }

        List<StepDto> steps = orEmpty(response.route().legs()).stream()
                .flatMap(leg -> orEmpty(leg.steps()).stream())
                .map(this::toWalkStep)
                .toList();
        List<PointDto> path = steps.stream()
                .flatMap(step -> step.path().stream())
                .toList();

        return new DirectionsResponseDto(
                DirectionsMode.WALK,
                totalDistance(response.route().properties()),
                totalTime(response.route().properties()),
                path,
                steps,
                response.route().properties() == null ? null : response.route().properties().landingUrl(),
                null,
                null
        );
    }

    public List<DirectionsResponseDto> getTransitRoutes(PointDto origin, PointDto destination) {
        TransitResponse response = fetch(
                MAP_HOST, "/v2/routing/publictraffic", mapParams(origin, destination), TransitResponse.class
        );

        List<TransitRoute> routes = orEmpty(response.routes());
        if (!STATUS_OK.equals(response.status()) || routes.isEmpty()) {
            throw routeNotFound(DirectionsMode.TRANSIT, response.status());
        }

        // landingURL은 후보별이 아니라 전체 대중교통 검색 결과 전체에 대해 하나만 내려온다.
        String landingUrl = response.properties() == null ? null : response.properties().landingURL();
        return routes.stream().map(route -> toTransitResult(route, landingUrl)).toList();
    }

    private DirectionsResponseDto toTransitResult(TransitRoute route, String landingUrl) {
        List<StepDto> steps = orEmpty(route.steps()).stream()
                .map(this::toStep)
                .toList();
        List<PointDto> path = steps.stream()
                .flatMap(step -> step.path().stream())
                .toList();

        return new DirectionsResponseDto(
                DirectionsMode.TRANSIT,
                totalDistance(route.properties()),
                totalTime(route.properties()),
                path,
                steps,
                landingUrl,
                toTransitInfo(route.properties()),
                null
        );
    }

    private TransitInfo toTransitInfo(TransitRouteProperties properties) {
        if (properties == null) {
            return null;
        }
        TransitFareRaw fare = properties.fare();
        return new TransitInfo(
                fare == null ? null : new TransitFare(fare.value(), fare.min(), fare.max()),
                orZero(properties.transfers()),
                properties.type()
        );
    }

    public DirectionsResponseDto car(PointDto origin, PointDto destination) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("origin", xy(origin));
        params.put("destination", xy(destination));
        params.put("priority", "RECOMMEND");

        CarResponse response = fetch(NAVI_HOST, "/v1/directions", params, CarResponse.class);

        List<CarRoute> routes = orEmpty(response.routes());
        CarRoute route = routes.isEmpty() ? null : routes.getFirst();
        if (route == null || !Integer.valueOf(CAR_RESULT_SUCCESS).equals(route.result_code())
                || route.summary() == null) {
            throw routeNotFound(DirectionsMode.CAR, route == null ? null : String.valueOf(route.result_code()));
        }

        List<PointDto> path = orEmpty(route.sections()).stream()
                .flatMap(section -> orEmpty(section.roads()).stream())
                .flatMap(road -> toFlatPoints(road.vertexes()).stream())
                .toList();
        List<CarGuideDto> guides = orEmpty(route.sections()).stream()
                .flatMap(section -> orEmpty(section.guides()).stream())
                .map(this::toCarGuide)
                .toList();
        CarFareRaw fare = route.summary().fare();

        return new DirectionsResponseDto(
                DirectionsMode.CAR,
                orZero(route.summary().distance()),
                orZero(route.summary().duration()),
                path,
                List.of(),
                // 카카오모빌리티 길찾기 응답에는 카카오맵 딥링크가 없다.
                null,
                null,
                new CarInfo(fare == null ? null : new CarFare(fare.taxi(), fare.toll()), guides)
        );
    }

    private CarGuideDto toCarGuide(CarGuide guide) {
        PointDto point = guide.x() == null || guide.y() == null ? null : new PointDto(guide.y(), guide.x());
        return new CarGuideDto(
                guide.name(),
                guide.guidance(),
                orZero(guide.distance()),
                orZero(guide.duration()),
                point
        );
    }

    private Map<String, String> mapParams(PointDto origin, PointDto destination) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("start_x", String.valueOf(origin.longitude()));
        params.put("start_y", String.valueOf(origin.latitude()));
        params.put("end_x", String.valueOf(destination.longitude()));
        params.put("end_y", String.valueOf(destination.latitude()));
        return params;
    }

    private String xy(PointDto point) {
        return point.longitude() + "," + point.latitude();
    }

    private <T> T fetch(String host, String path, Map<String, String> params, Class<T> responseType) {
        T response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.scheme("https").host(host).path(path);
                        params.forEach(uriBuilder::queryParam);
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .body(responseType);
        } catch (RestClientResponseException exception) {
            // 쿼터 초과, 권한 없음(카카오맵 사용 설정 OFF 등)을 구분할 수 있도록 상태와 본문을 남깁니다.
            log.warn("카카오 길찾기 API가 오류를 반환했습니다. host={}, path={}, status={}, body={}",
                    host, path, exception.getStatusCode().value(), exception.getResponseBodyAsString());
            throw new DirectionsException(ErrorCode.DIRECTIONS_SERVICE_UNAVAILABLE);
        } catch (ResourceAccessException exception) {
            log.warn("카카오 길찾기 API에 연결하지 못했습니다. host={}, path={}", host, path, exception);
            throw new DirectionsException(isTimeout(exception)
                    ? ErrorCode.DIRECTIONS_TIMEOUT
                    : ErrorCode.DIRECTIONS_SERVICE_UNAVAILABLE);
        } catch (RestClientException exception) {
            log.warn("카카오 길찾기 API 응답을 처리하지 못했습니다. host={}, path={}", host, path, exception);
            throw new DirectionsException(ErrorCode.DIRECTIONS_SERVICE_UNAVAILABLE);
        }

        if (response == null) {
            log.warn("카카오 길찾기 API 응답 본문이 비어 있습니다. host={}, path={}", host, path);
            throw new DirectionsException(ErrorCode.DIRECTIONS_SERVICE_UNAVAILABLE);
        }
        return response;
    }

    private boolean isTimeout(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private DirectionsException routeNotFound(DirectionsMode mode, String reason) {
        log.info("카카오 길찾기 경로를 찾지 못했습니다. mode={}, reason={}", mode, reason);
        return new DirectionsException(ErrorCode.DIRECTIONS_ROUTE_NOT_FOUND);
    }

    private StepDto toStep(TransitStep step) {
        TransitStepProperties properties = step.properties();
        if (properties == null) {
            return new StepDto(null, null, 0, 0, List.of(), List.of(), toPoints(step.path()));
        }
        return new StepDto(
                properties.type(),
                properties.guidance(),
                orZero(properties.distance()),
                orZero(properties.time()),
                orEmpty(properties.stops()).stream().map(Named::name).toList(),
                orEmpty(properties.vehicles()).stream().map(Named::name).toList(),
                toPoints(step.path())
        );
    }

    private StepDto toWalkStep(WalkStep step) {
        WalkStepProperties properties = step.properties();
        if (properties == null) {
            return new StepDto("WALKING", null, 0, 0, List.of(), List.of(), toPoints(step.path()));
        }
        return new StepDto(
                "WALKING",
                properties.guidance(),
                orZero(properties.distance()),
                orZero(properties.time()),
                List.of(),
                List.of(),
                toPoints(step.path())
        );
    }

    /** 카카오는 [경도, 위도] 순서의 좌표 쌍 목록을 줍니다. */
    private List<PointDto> toPoints(Path path) {
        if (path == null) {
            return List.of();
        }
        return orEmpty(path.points()).stream()
                .filter(point -> point != null && point.size() >= 2)
                .map(point -> new PointDto(point.get(1), point.get(0)))
                .toList();
    }

    /** 자동차 경로는 [경도, 위도, 경도, 위도, ...]로 펼쳐진 배열입니다. */
    private List<PointDto> toFlatPoints(List<Double> vertexes) {
        List<Double> values = orEmpty(vertexes);
        return IntStream.range(0, values.size() / 2)
                .mapToObj(index -> new PointDto(values.get(index * 2 + 1), values.get(index * 2)))
                .toList();
    }

    private int totalDistance(WalkRouteProperties properties) {
        return properties == null ? 0 : orZero(properties.totalDistance());
    }

    private int totalTime(WalkRouteProperties properties) {
        return properties == null ? 0 : orZero(properties.totalTime());
    }

    private int totalDistance(TransitRouteProperties properties) {
        return properties == null ? 0 : orZero(properties.totalDistance());
    }

    private int totalTime(TransitRouteProperties properties) {
        return properties == null ? 0 : orZero(properties.totalTime());
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private record Path(List<List<Double>> points) {}

    private record Named(String name) {}

    private record WalkResponse(String status, WalkRoute route) {}

    private record WalkRoute(WalkRouteProperties properties, List<WalkLeg> legs) {}

    private record WalkRouteProperties(Integer totalDistance, Integer totalTime, String landingUrl) {}

    private record WalkLeg(List<WalkStep> steps) {}

    private record WalkStep(WalkStepProperties properties, Path path) {}

    private record WalkStepProperties(Integer distance, String guidance, Integer time) {}

    private record TransitResponse(String status, TransitResponseProperties properties, List<TransitRoute> routes) {}

    private record TransitResponseProperties(String landingURL) {}

    private record TransitRoute(TransitRouteProperties properties, List<TransitStep> steps) {}

    private record TransitRouteProperties(
            Integer totalDistance,
            Integer totalTime,
            Integer transfers,
            TransitFareRaw fare,
            String type
    ) {}

    private record TransitFareRaw(Integer value, Integer min, Integer max) {}

    private record TransitStep(TransitStepProperties properties, Path path) {}

    private record TransitStepProperties(
            String type,
            String guidance,
            Integer distance,
            Integer time,
            List<Named> stops,
            List<Named> vehicles
    ) {}

    private record CarResponse(List<CarRoute> routes) {}

    private record CarRoute(Integer result_code, CarSummary summary, List<CarSection> sections) {}

    private record CarSummary(Integer distance, Integer duration, CarFareRaw fare) {}

    private record CarFareRaw(Integer taxi, Integer toll) {}

    private record CarSection(List<CarRoad> roads, List<CarGuide> guides) {}

    private record CarRoad(List<Double> vertexes) {}

    private record CarGuide(
            String name,
            Double x,
            Double y,
            Integer distance,
            Integer duration,
            String guidance
    ) {}
}
