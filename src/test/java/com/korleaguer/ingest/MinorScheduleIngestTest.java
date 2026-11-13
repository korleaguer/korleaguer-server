package com.korleaguer.ingest;

import com.korleaguer.client.MlbGameLogSplit;
import com.korleaguer.client.MlbMinorGameLog;
import com.korleaguer.client.MlbPerson;
import com.korleaguer.client.MlbScheduleGame;
import com.korleaguer.client.MlbStat;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.domain.MinorGameLog;
import com.korleaguer.domain.MinorGameLogRepository;
import com.korleaguer.domain.MinorGameSchedule;
import com.korleaguer.domain.PlayerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마이너 경기 일정(이슈 #145): 경기별 기록을 통째로 바꿔도 받아 둔 일정은 남고, 끝나 점수를 받은 경기는 다시 묻지 않는다.
 * 끝나지 않은 경기도 공식 날짜 7일이 지나면 더 묻지 않는다. 선수 팀이 홈인지 원정인지 모르면 점수를 남기지 않는다.
 * 이정후(샘플에서 마이너 경기가 없는 선수)에게 지어낸 오클라호마시티(238) 경기들을 넣어 본다.
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:minor-schedule-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class MinorScheduleIngestTest {

    private static final int SEASON = 2026;
    private static final Instant NOW = Instant.parse("2026-09-22T03:00:00Z");
    private static final MlbTeam OKC = new MlbTeam(238L, "Oklahoma City Comets", null, null, null, "Oklahoma City");
    private static final MlbTeam ISOTOPES = new MlbTeam(342L, "Albuquerque Isotopes", null, null, null, "Albuquerque");
    private static final MlbTeam CHIHUAHUAS = new MlbTeam(4904L, "El Paso Chihuahuas", null, null, null, "El Paso");

    @Autowired
    private IngestWriter writer;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private MinorGameLogRepository minorGameLogs;

    @Test
    void finishedGamesAreAskedOnceAndKeptWhenTheGameLogIsReplaced() {
        long lee = players.findByNameKo("이정후").orElseThrow().getId();
        MlbMinorGameLog log = new MlbMinorGameLog(List.of(
                split(900L, "2026-09-10", OKC, ISOTOPES, true, true),
                split(901L, "2026-09-19", OKC, ISOTOPES, true, true),
                split(902L, "2026-09-20", OKC, CHIHUAHUAS, false, false),
                split(903L, "2026-09-21", OKC, CHIHUAHUAS, false, null),
                split(904L, "2026-09-21", OKC, CHIHUAHUAS, false, null),
                split(905L, "2026-09-21", null, CHIHUAHUAS, null, null)), List.of());

        // 처음: 모두 일정을 모른다(오래된 900도 한 번은 묻는다)
        List<Long> asked = writer.saveMinorGameLog(lee, SEASON, log, NOW);
        assertThat(asked).containsExactly(900L, 901L, 902L, 903L, 904L, 905L);

        // 901: 끝남(오클라호마시티 홈 5:3), 902: 끝남(원정 2:6, 같은 번호의 연기 줄이 뒤에 또 왔다: 끝난 줄을 쓴다),
        // 903: 경기 중, 900·904: 응답에 없음, 905: 선수 팀을 모른다(팀 번호·홈 여부 없음)
        int saved = writer.saveMinorSchedule(lee, SEASON, asked, List.of(
                game(901L, "2026-09-20T00:05:00Z", "Final", "Final", ISOTOPES, 3, OKC, 5),
                game(902L, "2026-09-20T23:05:00Z", "Final", "Final", OKC, 2, CHIHUAHUAS, 6),
                game(902L, "2026-09-20T01:05:00Z", "Final", "Postponed", OKC, null, CHIHUAHUAS, null),
                game(903L, "2026-09-22T01:35:00Z", "Live", "In Progress", OKC, 1, CHIHUAHUAS, 0),
                game(905L, "2026-09-22T01:05:00Z", "Final", "Final", OKC, 4, CHIHUAHUAS, 2)));
        assertThat(saved).isEqualTo(4);

        Map<Long, MinorGameSchedule> stored = schedules(lee);
        assertThat(stored.get(901L)).isEqualTo(new MinorGameSchedule(Instant.parse("2026-09-20T00:05:00Z"), "final",
                5, 3, "Oklahoma City", "Albuquerque"));
        assertThat(stored.get(902L)).isEqualTo(new MinorGameSchedule(Instant.parse("2026-09-20T23:05:00Z"), "final",
                2, 6, "Oklahoma City", "El Paso"));
        assertThat(stored.get(903L).statusState()).isEqualTo("in_progress");
        assertThat(stored.get(900L)).isEqualTo(MinorGameSchedule.notFound());
        assertThat(stored.get(904L)).isEqualTo(MinorGameSchedule.notFound());
        // 어느 쪽 점수인지 모르면 점수를 남기지 않는다(뒤바뀐 점수 대신 빈 점수)
        assertThat(stored.get(905L)).isEqualTo(new MinorGameSchedule(Instant.parse("2026-09-22T01:05:00Z"), "final",
                null, null, null, null));

        // 다음 날 같은 기록을 다시 받아 통째로 바꿔도 받아 둔 일정은 그대로다. 다시 묻는 것은 끝나 점수를 받지 못한 최근 경기뿐이다
        // (오래된 900은 응답에 없었으니 더 묻지 않는다)
        assertThat(writer.saveMinorGameLog(lee, SEASON, log, NOW.plusSeconds(86_400)))
                .containsExactly(903L, 904L, 905L);
        assertThat(schedules(lee)).isEqualTo(stored);
        MinorGameLog first = minorGameLogs.findSeason(List.of(lee), SEASON).get(1);
        assertThat(first.getGamePk()).isEqualTo(901L);
        assertThat(first.getTeamId()).isEqualTo(238L);
        assertThat(first.getOpponentId()).isEqualTo(342L);

        // 열흘 뒤에는 끝나지 않은 경기도 더 묻지 않는다
        assertThat(writer.saveMinorGameLog(lee, SEASON, log, NOW.plusSeconds(10 * 86_400))).isEmpty();
    }

    private Map<Long, MinorGameSchedule> schedules(long playerId) {
        return minorGameLogs.findSeason(List.of(playerId), SEASON).stream()
                .collect(Collectors.toMap(MinorGameLog::getGamePk, MinorGameLog::getSchedule));
    }

    private static MlbGameLogSplit split(long gamePk, String date, MlbTeam team, MlbTeam opponent, Boolean home,
                                         Boolean win) {
        MlbStat stat = new MlbStat(1, 1, 4, 1, 2, 0, 0, 0, 1, 0, 1, 0, 0, 0, 4, 2, null, null, null, null, null, null,
                null, null, null);
        return new MlbGameLogSplit(String.valueOf(SEASON), date, "R", home, win, team, opponent,
                new MlbGameLogSplit.GameRef(gamePk), MlbPerson.of(808982L, null, null), stat,
                new MlbGameLogSplit.Sport(11L, "AAA"));
    }

    private static MlbScheduleGame game(long gamePk, String gameDate, String abstractState, String detailedState,
                                        MlbTeam away, Integer awayScore, MlbTeam home, Integer homeScore) {
        return new MlbScheduleGame(gamePk, "R", String.valueOf(SEASON), gameDate, gameDate.substring(0, 10),
                new MlbScheduleGame.Status(abstractState, null, detailedState),
                new MlbScheduleGame.Teams(new MlbScheduleGame.Side(away, awayScore),
                        new MlbScheduleGame.Side(home, homeScore)), null, "N", 1);
    }
}
