package com.korleaguer.client;

import com.korleaguer.support.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 네트워크 없이 MockRestServiceServer로 응답을 흉내 내서 HttpBdlClient를 확인한다. */
class HttpBdlClientTest {

    private static final String GAME_PAGE = """
            {"data":[{"id":1003,"date":"2026-09-26T20:05:00.000Z","status_state":"final",
                      "home_team":{"id":24,"abbreviation":"SF"},"away_team":{"id":9,"abbreviation":"COL"},
                      "home_team_data":{"runs":5},"away_team_data":{"runs":3}}],
             "meta":{"per_page":100}}
            """;

    private final List<Duration> sleeps = new ArrayList<>();
    private MockRestServiceServer server;
    private HttpBdlClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://bdl.test")
                .defaultHeader("Authorization", "test-key");
        server = MockRestServiceServer.bindTo(builder).build();
        // 가짜 시계: 실제로 기다리지 않는다.
        long[] now = {0};
        RateLimiter limiter = new RateLimiter(100, () -> now[0], nanos -> now[0] += nanos);
        client = new HttpBdlClient(builder.build(), limiter, sleeps::add);
    }

    @Test
    void followsNextCursorUntilLastPage() {
        server.expect(requestTo(allOf(containsString("/mlb/v1/players"), containsString("search=Kim"),
                        containsString("per_page=100"))))
                .andExpect(header("Authorization", "test-key"))
                .andRespond(withSuccess("""
                        {"data":[{"id":1,"full_name":"Ha-Seong Kim","birth_place":"Bucheon, South Korea"}],
                         "meta":{"next_cursor":7,"per_page":100}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("cursor=7")))
                .andRespond(withSuccess("""
                        {"data":[{"id":2,"full_name":"Hyeseong Kim"}],"meta":{"per_page":100}}
                        """, MediaType.APPLICATION_JSON));

        List<BdlPlayer> players = client.searchPlayers("Kim");

        assertThat(players).extracting(BdlPlayer::id).containsExactly(1L, 2L);
        assertThat(players.getFirst().birthPlace()).isEqualTo("Bucheon, South Korea");
        assertThat(client.requestCount()).isEqualTo(2);
        server.verify();
    }

    @Test
    void stopsWhenCursorDoesNotMove() {
        server.expect(requestTo(containsString("/mlb/v1/players")))
                .andRespond(withSuccess("""
                        {"data":[{"id":1}],"meta":{"next_cursor":7}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("cursor=7")))
                .andRespond(withSuccess("""
                        {"data":[{"id":2}],"meta":{"next_cursor":7}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.searchPlayers("Kim")).hasSize(2);
        server.verify(); // 세 번째 요청은 보내지 않는다
    }

    @Test
    void retriesAfterTooManyRequestsWithCappedWait() {
        server.expect(requestTo(containsString("/mlb/v1/games")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "999"));
        server.expect(requestTo(allOf(containsString("/mlb/v1/games"), containsString("team_ids%5B%5D=24"),
                        containsString("seasons%5B%5D=2026"))))
                .andRespond(withSuccess(GAME_PAGE, MediaType.APPLICATION_JSON));

        List<BdlGame> games = client.getGames(List.of(24L), List.of(2026), List.of());

        assertThat(games.getFirst().homeTeamData().runs()).isEqualTo(5);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(120)); // 999초는 120초로 자른다
        assertThat(client.requestCount()).isEqualTo(2);
        server.verify();
    }

    @Test
    void retriesServerErrorAndNetworkError() {
        server.expect(requestTo(containsString("/mlb/v1/games")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(containsString("/mlb/v1/games")))
                .andRespond(withException(new IOException("connection reset")));
        server.expect(requestTo(containsString("/mlb/v1/games")))
                .andRespond(withSuccess(GAME_PAGE, MediaType.APPLICATION_JSON));

        assertThat(client.getGames(List.of(24L), List.of(2026), List.of())).hasSize(1);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(10));
        server.verify();
    }

    @Test
    void givesUpAfterThreeRetries() {
        for (int i = 0; i < 4; i++) {
            server.expect(requestTo(containsString("/mlb/v1/games")))
                    .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        }

        assertThatThrownBy(() -> client.getGames(List.of(24L), List.of(2026), List.of()))
                .isInstanceOf(BdlApiException.class);
        assertThat(client.requestCount()).isEqualTo(4);
    }

    @Test
    void statsUnauthorizedMeansStatsUnavailable() {
        server.expect(requestTo(containsString("/mlb/v1/stats")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"Unauthorized\"}"));

        assertThatThrownBy(() -> client.getStats(List.of(2101L), List.of(2026), List.of()))
                .isInstanceOf(StatsUnavailableException.class);
    }

    @Test
    void readsPitchingColumns() {
        server.expect(requestTo(containsString("/mlb/v1/stats")))
                .andRespond(withSuccess("""
                        {"data":[{"player":{"id":2105},"team":{"id":17},"game_id":4003,
                                  "ip":0.2,"p_hits":2,"p_runs":2,"er":2,"p_bb":1,"p_k":0,"pitching_outs":null,"losses":1}],
                         "meta":{"per_page":100}}
                        """, MediaType.APPLICATION_JSON));

        BdlStats stats = client.getStats(List.of(2105L), List.of(2026), List.of()).getFirst();

        assertThat(stats.ip()).isEqualTo(0.2);
        assertThat(stats.pHits()).isEqualTo(2);
        assertThat(stats.pBb()).isEqualTo(1);
        assertThat(stats.pK()).isZero();
        assertThat(stats.pitchingOuts()).isNull();
    }

    @Test
    void getPlayerByIdAndNotFound() {
        server.expect(requestTo("http://bdl.test/mlb/v1/players/2103"))
                .andRespond(withSuccess("""
                        {"data":{"id":2103,"full_name":"Ha-Seong Kim","last_name":"Kim"}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://bdl.test/mlb/v1/players/1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.getPlayer(2103).fullName()).isEqualTo("Ha-Seong Kim");
        assertThat(client.getPlayer(1)).isNull();
    }
}
