package com.korleaguer.client;

import com.korleaguer.support.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 네트워크 없이 HttpMlbClient를 확인한다. 응답은 statsapi.mlb.com에서 받아 둔 실제 응답(src/test/resources/mlb)이다.
 * - person-808970.json: /api/v1/people/808970?hydrate=currentTeam (2026-11-10)
 * - transactions-808970.json: /api/v1/transactions?playerId=808970&startDate=2025-01-01&endDate=2026-12-31
 * - team-1960.json: /api/v1/teams/1960
 */
class HttpMlbClientTest {

    private static final long SECOND = Duration.ofSeconds(1).toNanos();

    // 가짜 시계: 잠든 만큼만 시간이 흐른다.
    private final long[] now = {0};
    private MockRestServiceServer server;
    private HttpMlbClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://mlb.test");
        server = MockRestServiceServer.bindTo(builder).build();
        RateLimiter limiter = new RateLimiter(1, Duration.ofSeconds(1), () -> now[0], nanos -> now[0] += nanos);
        client = new HttpMlbClient(builder.build(), limiter);
    }

    @Test
    void readsPersonWithCurrentTeam() {
        server.expect(requestTo(allOf(containsString("/api/v1/people/808970"), containsString("hydrate=currentTeam"))))
                .andRespond(withSuccess(fixture("person-808970.json"), MediaType.APPLICATION_JSON));

        MlbPerson person = client.getPerson(808970);

        assertThat(person.id()).isEqualTo(808970L);
        assertThat(person.fullName()).isEqualTo("Woo-Suk Go");
        assertThat(person.currentTeam().id()).isEqualTo(1960L);
        assertThat(person.currentTeam().name()).isEqualTo("St. Paul Saints");
        assertThat(person.currentTeam().parentOrgId()).isEqualTo(142L);
        // currentTeam에는 리그 단계(sport)가 없다. 그래서 팀 조회가 따로 필요하다.
        assertThat(person.currentTeam().sportId()).isNull();
        server.verify();
    }

    @Test
    void readsTransactions() {
        server.expect(requestTo(allOf(containsString("/api/v1/transactions"), containsString("playerId=808970"),
                        containsString("startDate=2025-01-01"), containsString("endDate=2026-12-31"))))
                .andRespond(withSuccess(fixture("transactions-808970.json"), MediaType.APPLICATION_JSON));

        List<MlbTransaction> moves = client.getTransactions(808970, LocalDate.of(2025, 1, 1),
                LocalDate.of(2026, 12, 31));

        assertThat(moves).hasSize(35);
        MlbTransaction outright = moves.stream().filter(m -> m.id() == 940170L).findFirst().orElseThrow();
        assertThat(outright.date()).isEqualTo("2026-08-30");
        assertThat(outright.typeCode()).isEqualTo("OUT");
        assertThat(outright.typeDesc()).isEqualTo("Outrighted");
        assertThat(outright.description()).isEqualTo("Minnesota Twins sent RHP Woo-Suk Go outright to St. Paul Saints.");
        assertThat(outright.fromTeam().id()).isEqualTo(142L);
        assertThat(outright.toTeam().name()).isEqualTo("St. Paul Saints");
        assertThat(outright.person().id()).isEqualTo(808970L);
        // toTeam만 있는 줄
        MlbTransaction designated = moves.stream().filter(m -> m.id() == 939845L).findFirst().orElseThrow();
        assertThat(designated.fromTeam()).isNull();
        assertThat(designated.toTeam().id()).isEqualTo(142L);
    }

    @Test
    void readsTeamSport() {
        server.expect(requestTo("http://mlb.test/api/v1/teams/1960"))
                .andRespond(withSuccess(fixture("team-1960.json"), MediaType.APPLICATION_JSON));

        MlbTeam team = client.getTeam(1960);

        assertThat(team.name()).isEqualTo("St. Paul Saints");
        assertThat(team.abbreviation()).isEqualTo("STP");
        assertThat(team.sportId()).isEqualTo(11L);
        assertThat(team.parentOrgId()).isEqualTo(142L);
    }

    @Test
    void notFoundIsNull() {
        server.expect(requestTo(containsString("/api/v1/people/1"))).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(containsString("/api/v1/teams/2"))).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.getPerson(1)).isNull();
        assertThat(client.getTeam(2)).isNull();
    }

    @Test
    void forbiddenAndTooManyRequestsStopTheRun() {
        server.expect(requestTo(containsString("/api/v1/people/1"))).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo(containsString("/api/v1/teams/1"))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.getPerson(1)).isInstanceOf(MlbBlockedException.class);
        assertThatThrownBy(() -> client.getTeam(1)).isInstanceOf(MlbBlockedException.class);
        assertThat(client.requestCount()).isEqualTo(2); // 다시 시도하지 않는다
    }

    @Test
    void otherFailuresAreNotBlocked() {
        server.expect(requestTo(containsString("/api/v1/people/1"))).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(containsString("/api/v1/people/2")))
                .andRespond(withException(new IOException("connection reset")));

        assertThatThrownBy(() -> client.getPerson(1))
                .isInstanceOf(MlbApiException.class).isNotInstanceOf(MlbBlockedException.class);
        assertThatThrownBy(() -> client.getPerson(2))
                .isInstanceOf(MlbApiException.class).isNotInstanceOf(MlbBlockedException.class);
        server.verify(); // 다시 시도하지 않는다
    }

    @Test
    void waitsOneSecondBetweenRequests() {
        server.expect(requestTo(containsString("/api/v1/teams/1960")))
                .andRespond(withSuccess(fixture("team-1960.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/api/v1/teams/1960")))
                .andRespond(withSuccess(fixture("team-1960.json"), MediaType.APPLICATION_JSON));

        client.getTeam(1960);
        long first = now[0];
        client.getTeam(1960);

        assertThat(now[0] - first).isGreaterThanOrEqualTo(SECOND);
        assertThat(client.requestCount()).isEqualTo(2);
    }

    private static String fixture(String name) {
        try {
            return new ClassPathResource("mlb/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
