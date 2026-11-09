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
 * 네트워크 없이 HttpMlbClient를 확인한다. 응답은 statsapi.mlb.com에서 받아 둔 실제 응답(src/test/resources/mlb, 2026-11-10)이다.
 * - people-batch.json: /api/v1/people?personIds=(명단 6명)&hydrate=currentTeam
 * - transactions-808970.json: /api/v1/transactions?playerId=808970&startDate=2025-01-01&endDate=2026-12-31
 * - team-1960.json: /api/v1/teams/1960
 * - gamelog-hitting-808975.json: /api/v1/people/808975/stats?stats=gameLog&group=hitting&season=2026 (앞 3경기만 남김)
 * - gamelog-pitching-808970.json: /api/v1/people/808970/stats?stats=gameLog&group=pitching&season=2026
 * - schedule-137-119-2026.json: /api/v1/schedule?sportId=1&teamId=137,119&season=2026 (경기 몇 개만 남김)
 * - boxscore-823165.json: /api/v1/game/823165/boxscore (선수 몇 명과 쓰는 칸만 남김)
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
    void readsPeopleInOneRequest() {
        server.expect(requestTo(allOf(containsString("/api/v1/people?"),
                        containsString("personIds=673490,678225,808970"), containsString("hydrate=currentTeam"))))
                .andRespond(withSuccess(fixture("people-batch.json"), MediaType.APPLICATION_JSON));

        List<MlbPerson> people = client.getPeople(List.of(808970L, 673490L, 678225L));

        assertThat(people).hasSize(6);
        MlbPerson go = people.stream().filter(p -> p.id() == 808970L).findFirst().orElseThrow();
        assertThat(go.fullName()).isEqualTo("Woo-Suk Go");
        assertThat(go.birthDate()).isEqualTo("1998-08-06");
        assertThat(go.height()).isEqualTo("5' 11\"");
        assertThat(go.weight()).isEqualTo(198);
        assertThat(go.primaryPosition().abbreviation()).isEqualTo("P");
        assertThat(go.pitchHand().description()).isEqualTo("Right");
        assertThat(go.mlbDebutDate()).isEqualTo("2026-07-09");
        assertThat(go.currentTeam().id()).isEqualTo(1960L);
        assertThat(go.currentTeam().name()).isEqualTo("St. Paul Saints");
        assertThat(go.currentTeam().parentOrgId()).isEqualTo(142L);
        // currentTeam에는 리그 단계(sport)가 없다. 그래서 마이너 팀은 팀 조회가 따로 필요하다.
        assertThat(go.currentTeam().sportId()).isNull();
        MlbPerson lee = people.stream().filter(p -> p.id() == 808982L).findFirst().orElseThrow();
        assertThat(lee.primaryPosition().abbreviation()).isEqualTo("RF");
        assertThat(lee.batSide().description()).isEqualTo("Left");
        assertThat(lee.birthCity()).isEqualTo("Seoul");
        assertThat(lee.birthCountry()).isEqualTo("Republic of Korea");
        server.verify();
    }

    @Test
    void readsHittingGameLog() {
        server.expect(requestTo(allOf(containsString("/api/v1/people/808975/stats"), containsString("stats=gameLog"),
                        containsString("group=hitting"), containsString("season=2026"))))
                .andRespond(withSuccess(fixture("gamelog-hitting-808975.json"), MediaType.APPLICATION_JSON));

        List<MlbGameLogSplit> splits = client.getGameLog(808975, MlbClient.Group.HITTING, 2026);

        assertThat(splits).hasSize(3);
        MlbGameLogSplit first = splits.getFirst();
        assertThat(first.gamePk()).isEqualTo(822756L);
        assertThat(first.date()).isEqualTo("2026-04-05");
        assertThat(first.gameType()).isEqualTo("R");
        assertThat(first.isHome()).isFalse();
        assertThat(first.team().id()).isEqualTo(119L);
        assertThat(first.opponent().id()).isEqualTo(120L);
        assertThat(first.player().id()).isEqualTo(808975L);
        assertThat(first.stat().gamesPlayed()).isEqualTo(1);
        assertThat(first.stat().atBats()).isZero();
    }

    @Test
    void readsPitchingGameLog() {
        server.expect(requestTo(allOf(containsString("/api/v1/people/808970/stats"), containsString("group=pitching"))))
                .andRespond(withSuccess(fixture("gamelog-pitching-808970.json"), MediaType.APPLICATION_JSON));

        List<MlbGameLogSplit> splits = client.getGameLog(808970, MlbClient.Group.PITCHING, 2026);

        assertThat(splits).hasSize(4);
        MlbStat debut = splits.getFirst().stat();
        assertThat(splits.getFirst().gamePk()).isEqualTo(823683L);
        assertThat(debut.outs()).isEqualTo(3);
        assertThat(debut.inningsPitched()).isEqualTo("1.0");
        assertThat(debut.earnedRuns()).isEqualTo(1);
        assertThat(debut.homeRuns()).isEqualTo(1);
        assertThat(debut.strikeOuts()).isEqualTo(1);
        assertThat(debut.numberOfPitches()).isEqualTo(18);
        assertThat(debut.gamesStarted()).isZero();
    }

    @Test
    void readsSeasonScheduleForSeveralTeams() {
        server.expect(requestTo(allOf(containsString("/api/v1/schedule"), containsString("sportId=1"),
                        containsString("teamId=119,137"), containsString("season=2026"))))
                .andRespond(withSuccess(fixture("schedule-137-119-2026.json"), MediaType.APPLICATION_JSON));

        List<MlbScheduleGame> games = client.getSeasonSchedule(List.of(137L, 119L), 2026);

        assertThat(games).extracting(MlbScheduleGame::gameType).contains("R", "D", "S");
        MlbScheduleGame opener = games.stream().filter(g -> g.gamePk() == 823244L).findFirst().orElseThrow();
        assertThat(opener.gameDate()).isEqualTo("2026-03-26T00:05:00Z");
        assertThat(opener.officialDate()).isEqualTo("2026-03-25");
        assertThat(opener.status().abstractGameState()).isEqualTo("Final");
        assertThat(opener.teams().home().team().id()).isEqualTo(137L);
        assertThat(opener.teams().away().score()).isEqualTo(7);
        assertThat(opener.venue().name()).isEqualTo("Oracle Park");
        // 연기된 경기는 같은 gamePk로 두 번 나온다(원래 날짜 + 다시 잡힌 날짜).
        assertThat(games).filteredOn(g -> g.gamePk() == 823471L)
                .extracting(g -> g.status().detailedState())
                .containsExactlyInAnyOrder("Postponed", "Final");
    }

    @Test
    void readsScheduleByDates() {
        server.expect(requestTo(allOf(containsString("/api/v1/schedule"), containsString("teamId=137"),
                        containsString("startDate=2026-09-25"), containsString("endDate=2026-09-26"))))
                .andRespond(withSuccess("{\"dates\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.getSchedule(List.of(137L), LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 26))).isEmpty();
    }

    @Test
    void readsBoxscore() {
        server.expect(requestTo("http://mlb.test/api/v1/game/823165/boxscore"))
                .andRespond(withSuccess(fixture("boxscore-823165.json"), MediaType.APPLICATION_JSON));

        MlbBoxscore box = client.getBoxscore(823165);

        MlbBoxscore.Side home = box.teams().home();
        assertThat(home.team().id()).isEqualTo(137L);
        assertThat(home.runs()).isEqualTo(3);
        assertThat(box.teams().away().runs()).isEqualTo(4);
        assertThat(home.pitchers()).startsWith(683363L, 701474L);
        // 선발 타자
        MlbBoxscore.Player starter = home.player(813841);
        assertThat(starter.battingOrder()).isEqualTo("100");
        assertThat(starter.gameStatus().isSubstitute()).isFalse();
        assertThat(starter.batting().atBats()).isEqualTo(4);
        assertThat(starter.batting().doubles()).isEqualTo(1);
        assertThat(starter.pitching()).isNull();
        // 교체 타자
        MlbBoxscore.Player sub = home.player(701852);
        assertThat(sub.battingOrder()).isEqualTo("801");
        assertThat(sub.gameStatus().isSubstitute()).isTrue();
        // 투수: 타격 기록은 빈 객체
        MlbBoxscore.Player starterPitcher = home.player(683363);
        assertThat(starterPitcher.batting()).isNull();
        assertThat(starterPitcher.pitching().outs()).isEqualTo(9);
        // 벤치 선수: 둘 다 없음
        assertThat(home.player(681916).gameStatus()).isNotNull();
        assertThat(home.player(681916).batting()).isNull();
        assertThat(home.player(681916).pitching()).isNull();
        assertThat(home.player(999)).isNull();
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
        server.expect(requestTo(containsString("/api/v1/game/1/boxscore"))).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(containsString("/api/v1/teams/2"))).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.getBoxscore(1)).isNull();
        assertThat(client.getTeam(2)).isNull();
    }

    @Test
    void forbiddenAndTooManyRequestsStopTheRun() {
        server.expect(requestTo(containsString("/api/v1/people"))).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo(containsString("/api/v1/teams/1"))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> client.getPeople(List.of(1L))).isInstanceOf(MlbBlockedException.class);
        assertThatThrownBy(() -> client.getTeam(1)).isInstanceOf(MlbBlockedException.class);
        assertThat(client.requestCount()).isEqualTo(2); // 다시 시도하지 않는다
    }

    @Test
    void otherFailuresAreNotBlocked() {
        server.expect(requestTo(containsString("/api/v1/game/1/boxscore"))).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(containsString("/api/v1/game/2/boxscore")))
                .andRespond(withException(new IOException("connection reset")));

        assertThatThrownBy(() -> client.getBoxscore(1))
                .isInstanceOf(MlbApiException.class).isNotInstanceOf(MlbBlockedException.class);
        assertThatThrownBy(() -> client.getBoxscore(2))
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
