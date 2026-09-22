package com.ubot.direction.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.ubot.common.ErrorCode;
import com.ubot.common.GlobalException;
import com.ubot.direction.dto.DirectionsMode;
import com.ubot.direction.dto.DirectionsResponseDto;
import com.ubot.direction.dto.DirectionsResponseDto.PointDto;
import com.ubot.direction.exception.DirectionsException;

@DisplayName("카카오 길찾기 Client 테스트")
class KakaoDirectionsClientTest {

    // 출발: 위도 37.5, 경도 127.0 / 도착: 위도 37.4, 경도 127.1
    private static final PointDto ORIGIN = new PointDto(37.5, 127.0);
    private static final PointDto DESTINATION = new PointDto(37.4, 127.1);

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final KakaoDirectionsClient client = new KakaoDirectionsClient(builder.build());

    @Test
    @DisplayName("도보 경로의 거리, 시간, 좌표, 구간별 안내와 카카오맵 딥링크를 변환한다")
    void mapsWalkRoute() {
        server.expect(requestTo(startsWith("https://dapi.kakao.com/v2/routing/walk")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("start_x", "127.0"))
                .andExpect(queryParam("start_y", "37.5"))
                .andExpect(queryParam("end_x", "127.1"))
                .andExpect(queryParam("end_y", "37.4"))
                .andRespond(withSuccess("""
                        {
                          "status": "OK",
                          "route": {
                            "properties": {"totalDistance": 4025, "totalTime": 3914, "landingUrl": "https://map.kakao.com"},
                            "legs": [
                              {"properties": {"distance": 4025, "time": 3914}, "steps": [
                                {"properties": {"distance": 93, "guidance": "출발", "time": 84, "x": 127.0, "y": 37.5},
                                 "path": {"points": [[127.0, 37.5], [127.05, 37.45]]}},
                                {"properties": {"distance": 10, "guidance": "도착", "time": 9, "x": 127.05, "y": 37.45},
                                 "path": {"points": [[127.05, 37.45], [127.1, 37.4]]}}
                              ]}
                            ]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        DirectionsResponseDto result = client.walk(ORIGIN, DESTINATION);

        assertThat(result.mode()).isEqualTo(DirectionsMode.WALK);
        assertThat(result.distanceMeters()).isEqualTo(4025);
        assertThat(result.durationSeconds()).isEqualTo(3914);
        assertThat(result.path()).containsExactly(
                new PointDto(37.5, 127.0),
                new PointDto(37.45, 127.05),
                new PointDto(37.45, 127.05),
                new PointDto(37.4, 127.1)
        );
        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(0).type()).isEqualTo("WALKING");
        assertThat(result.steps().get(0).guidance()).isEqualTo("출발");
        assertThat(result.steps().get(0).distanceMeters()).isEqualTo(93);
        assertThat(result.steps().get(1).guidance()).isEqualTo("도착");
        assertThat(result.landingUrl()).isEqualTo("https://map.kakao.com");
        assertThat(result.transitInfo()).isNull();
        assertThat(result.carInfo()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("도보 경로 상태가 OK가 아니면 경로 없음 예외가 발생한다")
    void throwsRouteNotFoundWhenWalkStatusIsNotOk() {
        server.expect(requestTo(startsWith("https://dapi.kakao.com/v2/routing/walk")))
                .andRespond(withSuccess("{\"status\": \"TOO_FAR_AWAY\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.walk(ORIGIN, DESTINATION))
                .isInstanceOf(DirectionsException.class)
                .extracting(exception -> ((GlobalException) exception).getErrorCode())
                .isEqualTo(ErrorCode.DIRECTIONS_ROUTE_NOT_FOUND);
    }

    @Test
    @DisplayName("대중교통은 카카오가 준 후보 경로 전부를 각각 도보·버스·지하철 구간으로 변환한다")
    void mapsAllTransitRouteCandidates() {
        server.expect(requestTo(startsWith("https://dapi.kakao.com/v2/routing/publictraffic")))
                .andExpect(queryParam("start_x", "127.0"))
                .andExpect(queryParam("end_y", "37.4"))
                .andRespond(withSuccess("""
                        {
                          "status": "OK",
                          "properties": {"total": 2, "landingURL": "https://map.kakao.com/link/by/traffic/..."},
                          "routes": [
                            {
                              "properties": {"type": "BUS", "totalDistance": 5000, "totalTime": 1800, "transfers": 0,
                                             "fare": {"value": 1500}},
                              "steps": [
                                {"properties": {"type": "WALKING", "guidance": "정류장까지 도보", "distance": 200, "time": 180},
                                 "path": {"points": [[127.0, 37.5], [127.01, 37.49]]}},
                                {"properties": {"type": "BUS", "guidance": "버스 탑승", "distance": 4800, "time": 1620,
                                                "stops": [{"name": "강남역"}, {"name": "역삼역"}],
                                                "vehicles": [{"name": "146", "type": "간선"}]},
                                 "path": {"points": [[127.01, 37.49], [127.1, 37.4]]}}
                              ]
                            },
                            {"properties": {"type": "SUBWAY", "totalDistance": 3000, "totalTime": 900, "transfers": 1,
                                             "fare": {"min": 1500, "max": 2500}},
                             "steps": [
                              {"properties": {"type": "SUBWAY", "guidance": "2호선 탑승", "distance": 3000, "time": 900},
                               "path": {"points": [[127.02, 37.48], [127.08, 37.41]]}}
                            ]}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<DirectionsResponseDto> results = client.getTransitRoutes(ORIGIN, DESTINATION);

        assertThat(results).hasSize(2);

        DirectionsResponseDto first = results.get(0);
        assertThat(first.mode()).isEqualTo(DirectionsMode.TRANSIT);
        assertThat(first.distanceMeters()).isEqualTo(5000);
        assertThat(first.durationSeconds()).isEqualTo(1800);
        assertThat(first.steps()).hasSize(2);
        assertThat(first.steps().get(0).type()).isEqualTo("WALKING");
        assertThat(first.steps().get(1).type()).isEqualTo("BUS");
        assertThat(first.steps().get(1).stops()).containsExactly("강남역", "역삼역");
        assertThat(first.steps().get(1).vehicles()).containsExactly("146");
        assertThat(first.path()).containsExactly(
                new PointDto(37.5, 127.0),
                new PointDto(37.49, 127.01),
                new PointDto(37.49, 127.01),
                new PointDto(37.4, 127.1)
        );
        // 정액 요금은 value만 채워진다. 딥링크는 후보별이 아니라 응답 전체 기준 하나를 그대로 받는다.
        assertThat(first.transitInfo().type()).isEqualTo("BUS");
        assertThat(first.transitInfo().transfers()).isZero();
        assertThat(first.transitInfo().fare().value()).isEqualTo(1500);
        assertThat(first.transitInfo().fare().min()).isNull();
        assertThat(first.landingUrl()).isEqualTo("https://map.kakao.com/link/by/traffic/...");
        assertThat(first.carInfo()).isNull();

        DirectionsResponseDto second = results.get(1);
        assertThat(second.mode()).isEqualTo(DirectionsMode.TRANSIT);
        assertThat(second.distanceMeters()).isEqualTo(3000);
        assertThat(second.durationSeconds()).isEqualTo(900);
        assertThat(second.steps()).hasSize(1);
        assertThat(second.steps().getFirst().type()).isEqualTo("SUBWAY");
        // 구간·환승 요금은 min/max만 채워진다.
        assertThat(second.transitInfo().transfers()).isEqualTo(1);
        assertThat(second.transitInfo().fare().min()).isEqualTo(1500);
        assertThat(second.transitInfo().fare().max()).isEqualTo(2500);
        assertThat(second.transitInfo().fare().value()).isNull();
        assertThat(second.landingUrl()).isEqualTo("https://map.kakao.com/link/by/traffic/...");

        server.verify();
    }

    @Test
    @DisplayName("대중교통 경로가 없으면 경로 없음 예외가 발생한다")
    void throwsRouteNotFoundWhenTransitHasNoRoutes() {
        server.expect(requestTo(startsWith("https://dapi.kakao.com/v2/routing/publictraffic")))
                .andRespond(withSuccess("{\"status\": \"NO_RESULTS\", \"routes\": []}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getTransitRoutes(ORIGIN, DESTINATION))
                .isInstanceOf(DirectionsException.class)
                .extracting(exception -> ((GlobalException) exception).getErrorCode())
                .isEqualTo(ErrorCode.DIRECTIONS_ROUTE_NOT_FOUND);
    }

    @Test
    @DisplayName("자동차 경로는 경도,위도 순서로 요청하고 펼쳐진 좌표 배열을 변환한다")
    void mapsCarRoute() {
        server.expect(requestTo(startsWith("https://apis-navi.kakaomobility.com/v1/directions")))
                .andExpect(queryParam("origin", "127.0,37.5"))
                .andExpect(queryParam("destination", "127.1,37.4"))
                .andRespond(withSuccess("""
                        {
                          "trans_id": "abc",
                          "routes": [
                            {
                              "result_code": 0,
                              "result_msg": "길찾기 성공",
                              "summary": {"distance": 19032, "duration": 3494, "fare": {"taxi": 22200, "toll": 0}},
                              "sections": [
                                {"distance": 10000, "duration": 1000,
                                 "roads": [
                                  {"name": "A로", "vertexes": [127.0, 37.5, 127.05, 37.45]},
                                  {"name": "B로", "vertexes": [127.05, 37.45, 127.1, 37.4]}
                                 ],
                                 "guides": [
                                  {"name": "출발지", "x": 127.0, "y": 37.5, "distance": 0, "duration": 0,
                                   "type": 100, "guidance": "출발지", "road_index": 0},
                                  {"name": "", "x": 127.05, "y": 37.45, "distance": 96, "duration": 40,
                                   "type": 2, "guidance": "우회전", "road_index": 1}
                                 ]}
                              ]
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        DirectionsResponseDto result = client.car(ORIGIN, DESTINATION);

        assertThat(result.mode()).isEqualTo(DirectionsMode.CAR);
        assertThat(result.distanceMeters()).isEqualTo(19032);
        assertThat(result.durationSeconds()).isEqualTo(3494);
        assertThat(result.path()).containsExactly(
                new PointDto(37.5, 127.0),
                new PointDto(37.45, 127.05),
                new PointDto(37.45, 127.05),
                new PointDto(37.4, 127.1)
        );
        assertThat(result.steps()).isEmpty();
        assertThat(result.landingUrl()).isNull();
        assertThat(result.transitInfo()).isNull();
        assertThat(result.carInfo().fare().taxi()).isEqualTo(22200);
        assertThat(result.carInfo().fare().toll()).isEqualTo(0);
        assertThat(result.carInfo().guides()).hasSize(2);
        assertThat(result.carInfo().guides().get(1).guidance()).isEqualTo("우회전");
        assertThat(result.carInfo().guides().get(1).distanceMeters()).isEqualTo(96);
        assertThat(result.carInfo().guides().get(1).point()).isEqualTo(new PointDto(37.45, 127.05));
        server.verify();
    }

    @Test
    @DisplayName("자동차 경로의 result_code가 0이 아니면 경로 없음 예외가 발생한다")
    void throwsRouteNotFoundWhenCarResultCodeIsNotSuccess() {
        server.expect(requestTo(startsWith("https://apis-navi.kakaomobility.com/v1/directions")))
                .andRespond(withSuccess("""
                        {"routes": [{"result_code": 104, "result_msg": "출발지와 도착지가 5 m 이내로 설정된 경우 경로 탐색 불가"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.car(ORIGIN, DESTINATION))
                .isInstanceOf(DirectionsException.class)
                .extracting(exception -> ((GlobalException) exception).getErrorCode())
                .isEqualTo(ErrorCode.DIRECTIONS_ROUTE_NOT_FOUND);
    }

    @Test
    @DisplayName("카카오가 오류 상태를 반환하면 서비스 이용 불가 예외가 발생한다")
    void throwsServiceUnavailableWhenKakaoReturnsErrorStatus() {
        server.expect(requestTo(startsWith("https://dapi.kakao.com/v2/routing/walk")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errorType\": \"RequestThrottled\", \"code\": -10}"));

        assertThatThrownBy(() -> client.walk(ORIGIN, DESTINATION))
                .isInstanceOf(DirectionsException.class)
                .extracting(exception -> ((GlobalException) exception).getErrorCode())
                .isEqualTo(ErrorCode.DIRECTIONS_SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("카카오 응답이 시간 초과되면 시간 초과 예외가 발생한다")
    void throwsTimeoutWhenKakaoDoesNotRespondInTime() {
        server.expect(requestTo(startsWith("https://dapi.kakao.com/v2/routing/walk")))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.walk(ORIGIN, DESTINATION))
                .isInstanceOf(DirectionsException.class)
                .extracting(exception -> ((GlobalException) exception).getErrorCode())
                .isEqualTo(ErrorCode.DIRECTIONS_TIMEOUT);
    }

    @Test
    @DisplayName("카카오에 연결하지 못하면 서비스 이용 불가 예외가 발생한다")
    void throwsServiceUnavailableWhenConnectionFails() {
        server.expect(requestTo(startsWith("https://dapi.kakao.com/v2/routing/walk")))
                .andRespond(withException(new ConnectException("Connection refused")));

        assertThatThrownBy(() -> client.walk(ORIGIN, DESTINATION))
                .isInstanceOf(DirectionsException.class)
                .extracting(exception -> ((GlobalException) exception).getErrorCode())
                .isEqualTo(ErrorCode.DIRECTIONS_SERVICE_UNAVAILABLE);
    }
}
