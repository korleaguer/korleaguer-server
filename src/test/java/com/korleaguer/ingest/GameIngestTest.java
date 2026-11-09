package com.korleaguer.ingest;

import com.korleaguer.client.MlbBoxscore;
import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbGameLogSplit;
import com.korleaguer.client.MlbPerson;
import com.korleaguer.client.MlbScheduleGame;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.client.MlbTransaction;
import com.korleaguer.client.SampleMlbClient;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Game;
import com.korleaguer.domain.GameRepository;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.korleaguer.client.MlbFixtures.batting;
import static com.korleaguer.client.MlbFixtures.boxscore;
import static com.korleaguer.client.MlbFixtures.game;
import static com.korleaguer.client.MlbFixtures.hitter;
import static com.korleaguer.client.MlbFixtures.hittingStat;
import static com.korleaguer.client.MlbFixtures.team;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 경기 갱신 전략: 진행 중인 경기는 깨어날 때마다, 끝난 경기는 끝난 뒤 한 번과 하루 한 번(시작 뒤 3일까지) 박스스코어를 받고,
 * 그 뒤에는 settled로 두어 다시 받지 않는다. 경기 전 경기는 박스스코어를 받지 않는다.
 * 김하성(ATL) 한 명, ATL 홈 경기 두 개(7001: 8/9, 7002: 8/10 미국 날짜)로 본다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:game-ingest-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "roster.baseball[0].name-ko=김하성",
        "roster.baseball[0].mlb-id=673490"
})
@ActiveProfiles("test")
class GameIngestTest {

    private static final MlbTeam ATL = team(144, "Atlanta Braves");
    private static final MlbTeam NYM = team(121, "New York Mets");
    private static final Instant START = Instant.parse("2026-08-10T18:00:00Z");

    @Autowired
    private IngestService ingestService;

    @Autowired
    private SwitchableClient client;

    @Autowired
    private MutableClock clock;

    @Autowired
    private GameRepository games;

    @Autowired
    private AppearanceRepository appearances;

    /** 데이터를 바꿔 끼울 수 있고, 박스스코어를 물은 경기를 적어 두는 클라이언트 */
    static class SwitchableClient implements MlbClient {

        volatile SampleMlbClient delegate;
        final List<Long> boxscoresAsked = new ArrayList<>();
        long requests;

        SwitchableClient(SampleMlbClient.Data data) {
            this.delegate = new SampleMlbClient(data);
        }

        void use(SampleMlbClient.Data data) {
            delegate = new SampleMlbClient(data);
        }

        List<Long> takeBoxscoresAsked() {
            List<Long> asked = List.copyOf(boxscoresAsked);
            boxscoresAsked.clear();
            return asked;
        }

        @Override
        public List<MlbPerson> getPeople(Collection<Long> personIds) {
            requests++;
            return delegate.getPeople(personIds);
        }

        @Override
        public List<MlbTransaction> getTransactions(long personId, LocalDate startDate, LocalDate endDate) {
            requests++;
            return delegate.getTransactions(personId, startDate, endDate);
        }

        @Override
        public MlbTeam getTeam(long teamId) {
            requests++;
            return delegate.getTeam(teamId);
        }

        @Override
        public List<MlbGameLogSplit> getGameLog(long personId, Group group, int season) {
            requests++;
            return delegate.getGameLog(personId, group, season);
        }

        @Override
        public List<MlbScheduleGame> getSeasonSchedule(Collection<Long> teamIds, int season) {
            requests++;
            return delegate.getSeasonSchedule(teamIds, season);
        }

        @Override
        public List<MlbScheduleGame> getSchedule(Collection<Long> teamIds, LocalDate startDate, LocalDate endDate) {
            requests++;
            return delegate.getSchedule(teamIds, startDate, endDate);
        }

        @Override
        public MlbBoxscore getBoxscore(long gamePk) {
            requests++;
            boxscoresAsked.add(gamePk);
            return delegate.getBoxscore(gamePk);
        }

        @Override
        public long requestCount() {
            return requests;
        }
    }

    @TestConfiguration
    static class Scenario {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START);
        }

        @Bean
        @Primary
        SwitchableClient switchableClient() {
            return new SwitchableClient(data(scheduled7002(), Map.of()));
        }
    }

    @Test
    void liveEveryTimeFinalsOncePerDayThenSettled() {
        // 앱 시작 때 시즌 백필: gameLog 기록(3타수 1안타), 7001은 끝났지만 시작한 지 3일이 안 돼 굳지 않았다.
        assertThat(stored(7001).isSettled()).isFalse();
        assertThat(line(7001).getBatting().atBats()).isEqualTo(3);
        assertThat(ingestService.inSeason(clock.instant())).isTrue();

        // 8/10 18:00Z: 끝난 7001을 처음 한 번 받는다(기록·점수 정정). 경기 전인 7002는 받지 않는다.
        client.use(data(scheduled7002(), Map.of(7001L, box(4, 2, 2, 5))));
        ingestService.catchUp(true);
        assertThat(client.takeBoxscoresAsked()).containsExactly(7001L);
        assertThat(line(7001).getBatting().hits()).isEqualTo(2);
        assertThat(stored(7001).getHomeScore()).isEqualTo(5);

        // 8/10 23:40Z: 7002 진행 중 → 깨어날 때마다 받는다. 7001은 오늘 이미 받았다.
        clock.set(Instant.parse("2026-08-10T23:40:00Z"));
        client.use(data(live7002(), Map.of(7001L, box(4, 2, 2, 5), 7002L, box(2, 1, 1, 0))));
        ingestService.catchUp(true);
        assertThat(client.takeBoxscoresAsked()).containsExactly(7002L);
        assertThat(stored(7002).getStatusState()).isEqualTo("in_progress");
        ingestService.catchUp(true);
        assertThat(client.takeBoxscoresAsked()).containsExactly(7002L);

        // 8/11 02:30Z(UTC 다음 날): 7002가 끝남 → 한 번 더, 7001은 하루 한 번 다시 확인
        clock.set(Instant.parse("2026-08-11T02:30:00Z"));
        client.use(data(final7002(), Map.of(7001L, box(4, 2, 2, 5), 7002L, box(4, 2, 3, 5))));
        ingestService.catchUp(true);
        assertThat(client.takeBoxscoresAsked()).containsExactlyInAnyOrder(7001L, 7002L);
        assertThat(stored(7002).getStatusState()).isEqualTo("final");
        assertThat(line(7002).getBatting().atBats()).isEqualTo(4);

        // 같은 날 다시 깨어나면 받지 않는다.
        clock.set(Instant.parse("2026-08-11T05:00:00Z"));
        ingestService.catchUp(true);
        assertThat(client.takeBoxscoresAsked()).isEmpty();

        // 8/13 00:00Z: 7001은 시작한 지 3일이 지나 마지막으로 받고 settled, 7002는 아직
        clock.set(Instant.parse("2026-08-13T00:00:00Z"));
        ingestService.catchUp(true);
        assertThat(client.takeBoxscoresAsked()).containsExactlyInAnyOrder(7001L, 7002L);
        assertThat(stored(7001).isSettled()).isTrue();
        assertThat(stored(7002).isSettled()).isFalse();

        // 8/14 00:00Z: settled인 7001은 받지 않는다.
        clock.set(Instant.parse("2026-08-14T00:00:00Z"));
        ingestService.catchUp(true);
        assertThat(client.takeBoxscoresAsked()).containsExactly(7002L);
        assertThat(stored(7002).isSettled()).isTrue();

        // 8/15: 받을 것 없음. 앞뒤 3일에 경기가 없으니 비시즌으로 본다.
        clock.set(Instant.parse("2026-08-15T00:00:00Z"));
        ingestService.catchUp(true);
        assertThat(client.takeBoxscoresAsked()).isEmpty();
        assertThat(ingestService.inSeason(clock.instant())).isFalse();
    }

    private Game stored(long gamePk) {
        return games.findById(gamePk).orElseThrow();
    }

    private Appearance line(long gamePk) {
        return appearances.findByPlayersAndGames(List.of(1L), List.of(gamePk)).getFirst();
    }

    private static MlbScheduleGame game7001() {
        return game(7001, NYM, ATL, "2026-08-09T23:10:00Z", "R", "Final", "F", "Final", 2, 4);
    }

    private static MlbScheduleGame scheduled7002() {
        return game(7002, NYM, ATL, "2026-08-10T23:10:00Z", "R", "Preview", "S", "Scheduled", null, null);
    }

    private static MlbScheduleGame live7002() {
        return game(7002, NYM, ATL, "2026-08-10T23:10:00Z", "R", "Live", "I", "In Progress", 1, 0);
    }

    private static MlbScheduleGame final7002() {
        return game(7002, NYM, ATL, "2026-08-10T23:10:00Z", "R", "Final", "F", "Final", 3, 5);
    }

    private static MlbBoxscore box(int atBats, int hits, int awayRuns, int homeRuns) {
        return boxscore(NYM, ATL, true, 673490, "600", hittingStat(atBats, hits), null, List.of(1L), awayRuns,
                homeRuns);
    }

    private static SampleMlbClient.Data data(MlbScheduleGame game7002, Map<Long, MlbBoxscore> boxscores) {
        MlbScheduleGame game7001 = game7001();
        return SampleMlbClient.Data.empty()
                .withPeople(List.of(hitter(673490, "Ha-Seong Kim", ATL)))
                .withSchedule(List.of(game7001, game7002))
                .withHittingLogs(List.of(batting(673490, ATL, game7001, 3, 1)))
                .withBoxscores(boxscores);
    }
}
