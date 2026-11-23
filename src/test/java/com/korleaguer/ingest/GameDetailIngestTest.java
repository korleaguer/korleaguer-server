package com.korleaguer.ingest;

import com.korleaguer.client.MlbApiException;
import com.korleaguer.client.MlbBlockedException;
import com.korleaguer.client.MlbBoxscore;
import com.korleaguer.client.MlbGameContent;
import com.korleaguer.client.MlbGameLogSplit;
import com.korleaguer.client.MlbPlayByPlay;
import com.korleaguer.client.MlbScheduleGame;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.client.SampleMlbClient;
import com.korleaguer.domain.GameDetail;
import com.korleaguer.domain.IngestLog;
import com.korleaguer.domain.IngestLogRepository;
import com.korleaguer.domain.IngestStatus;
import com.korleaguer.domain.PlayerGameDetail;
import com.korleaguer.domain.PlayerGameDetailRepository;
import com.korleaguer.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;

import static com.korleaguer.client.MlbFixtures.batting;
import static com.korleaguer.client.MlbFixtures.boxscore;
import static com.korleaguer.client.MlbFixtures.game;
import static com.korleaguer.client.MlbFixtures.hitter;
import static com.korleaguer.client.MlbFixtures.hittingStat;
import static com.korleaguer.client.MlbFixtures.team;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 경기 상세 수집(이슈 #229): 경기당 MLB 요청 수, 같은 경기 두 선수, 실행당 상한, 실패한 경기 건너뛰기, 굳은 상세는 다시 받지 않기,
 * 박스스코어를 받는 경기(진행 중·끝남)에서 같이 받기.
 * 김하성(ATL)·이정후(NYM으로 둔다) 두 명. 끝난 경기 7001~7012(7/1~7/12, 김하성은 모두, 이정후는 7012만)와 8/10 경기 7020.
 * 타석별 기록은 이정후 실제 응답(playbyplay-823898.json, 4타석)을 모든 경기에 쓴다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:game-detail-ingest-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "roster.baseball[0].name-ko=김하성",
        "roster.baseball[0].mlb-id=673490",
        "roster.baseball[1].name-ko=이정후",
        "roster.baseball[1].mlb-id=808982"
})
@ActiveProfiles("test")
class GameDetailIngestTest {

    private static final MlbTeam ATL = team(144, "Atlanta Braves");
    private static final MlbTeam NYM = team(121, "New York Mets");
    private static final long KIM = 1;
    private static final long LEE = 2;
    private static final long BROKEN_GAME = 7010;
    private static final Instant START = Instant.parse("2026-08-10T18:00:00Z");

    @Autowired
    private IngestService ingestService;

    @Autowired
    private DetailClient client;

    @Autowired
    private MutableClock clock;

    @Autowired
    private PlayerGameDetailRepository details;

    @Autowired
    private IngestLogRepository ingestLogs;

    private final JsonMapper json = JsonMapper.builder().build();

    /** 타석별 기록·영상 목록을 물은 경기를 적어 두고, 실패·거절을 흉내 내는 클라이언트 */
    static class DetailClient extends GameIngestTest.SwitchableClient {

        private static final JsonMapper JSON = JsonMapper.builder().build();

        final List<Long> playByPlayAsked = new ArrayList<>();
        final List<Long> contentAsked = new ArrayList<>();
        final Set<Long> broken = new HashSet<>(Set.of(BROKEN_GAME));
        /** MLB에 타석별 기록이 없는 경기(404 → null) */
        final Set<Long> missing = new HashSet<>();
        volatile boolean blocked;

        DetailClient(SampleMlbClient.Data data) {
            super(data);
        }

        @Override
        public MlbPlayByPlay getPlayByPlay(long gamePk) {
            requests++;
            playByPlayAsked.add(gamePk);
            if (blocked) {
                throw new MlbBlockedException("MLB refused: test", null);
            }
            if (broken.contains(gamePk)) {
                throw new MlbApiException("MLB request failed: test", null);
            }
            if (missing.contains(gamePk)) {
                return null;
            }
            return read("playbyplay-823898.json", MlbPlayByPlay.class);
        }

        @Override
        public MlbGameContent getGameContent(long gamePk) {
            requests++;
            contentAsked.add(gamePk);
            return read("content-823898.json", MlbGameContent.class);
        }

        void forget() {
            playByPlayAsked.clear();
            contentAsked.clear();
        }

        private static <T> T read(String name, Class<T> type) {
            try (InputStream in = new ClassPathResource("mlb/" + name).getInputStream()) {
                return JSON.readValue(in, type);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
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
        DetailClient detailClient() {
            return new DetailClient(data(scheduled7020(), Map.of()));
        }
    }

    @Test
    void detailsAreIngestedAFewGamesPerRunOncePerGameAndKeptOnceComplete() {
        // 앱 시작 때 따라잡기: 최근 경기부터 10경기(7012~7003)만 받는다. 경기마다 타석별 기록 1번 + 영상 목록 1번.
        // 7012는 우리 선수가 둘이지만 한 번만 묻는다. 7010은 MLB 오류라 건너뛴다(영상 목록도 묻지 않는다).
        assertThat(IngestService.DETAIL_GAMES_PER_RUN).isEqualTo(10);
        assertThat(client.playByPlayAsked).containsExactlyElementsOf(descending(7012, 7003));
        assertThat(client.contentAsked).containsExactlyElementsOf(
                descending(7012, 7003).stream().filter(pk -> pk != BROKEN_GAME).toList());
        assertThat(lastLog().getMessage()).contains("경기 상세 10개(경기 9개)");
        assertThat(saved(KIM, 7012).isComplete()).isTrue();
        assertThat(saved(LEE, 7012).isComplete()).isTrue();
        // 선수마다 자기 타석만: 이정후 4타석 + 그가 나온 하이라이트 1개, 김하성은 이 응답에 타석이 없다
        assertThat(detail(LEE, 7012).plays()).hasSize(4);
        assertThat(detail(LEE, 7012).highlights()).hasSize(1);
        assertThat(detail(LEE, 7012).clips()).isTrue();
        assertThat(detail(KIM, 7012).available()).isTrue();
        assertThat(detail(KIM, 7012).plays()).isEmpty();
        assertThat(details.findByPlayerIdAndGamePk(KIM, BROKEN_GAME)).isEmpty();
        assertThat(details.findByPlayerIdAndGamePk(KIM, 7002)).isEmpty();
        assertThat(details.count()).isEqualTo(10);

        // MLB가 거절하면(403/429) 그 실행을 멈춘다
        client.forget();
        client.blocked = true;
        IngestLog blocked = ingestService.catchUp(false);
        assertThat(blocked.getStatus()).isEqualTo(IngestStatus.BLOCKED);
        assertThat(client.playByPlayAsked).containsExactly(BROKEN_GAME);
        client.blocked = false;

        // 다음 실행: 실패했던 7010을 다시, 그리고 남은 7002·7001. 굳은 상세(7012 등)는 다시 받지 않는다.
        // 7001은 MLB에 타석별 기록이 없다(404): 영상 목록을 묻지 않고, 오래된 경기라 '없음'으로 굳혀 다시 묻지 않는다
        client.forget();
        client.broken.clear();
        client.missing.add(7001L);
        IngestLog second = ingestService.catchUp(false);
        assertThat(client.playByPlayAsked).containsExactly(7010L, 7002L, 7001L);
        assertThat(client.contentAsked).containsExactly(7010L, 7002L);
        assertThat(second.getMessage()).contains("경기 상세 2개(경기 2개)");
        assertThat(saved(KIM, 7001).isComplete()).isTrue();
        assertThat(detail(KIM, 7001).available()).isFalse();
        assertThat(details.count()).isEqualTo(13);

        // 다 받은 뒤에는 MLB를 부르지 않는다
        client.forget();
        long requests = client.requestCount();
        ingestService.catchUp(false);
        assertThat(client.requestCount()).isEqualTo(requests);

        // 8/10 23:40Z: 7020 진행 중 → 박스스코어를 받을 때마다 상세도 갱신한다(굳히지 않는다).
        // 진행 중에는 타석별 기록만 받고 영상 목록은 묻지 않는다(경기당 요청 1번)
        clock.set(Instant.parse("2026-08-10T23:40:00Z"));
        client.use(data(live7020(), Map.of(7020L, box(2, 1, 1, 0))));
        IngestLog live = ingestService.catchUp(true);
        assertThat(client.playByPlayAsked).containsExactly(7020L);
        assertThat(client.contentAsked).isEmpty();
        assertThat(live.getMessage()).contains("박스스코어 1개, 경기 상세 1개");
        assertThat(saved(KIM, 7020).isComplete()).isFalse();
        assertThat(detail(KIM, 7020).highlights()).isEmpty();
        ingestService.catchUp(true);
        assertThat(client.playByPlayAsked).containsExactly(7020L, 7020L);
        assertThat(client.contentAsked).isEmpty();

        // 8/11 02:30Z: 7020이 끝남 → 박스스코어와 같이 한 번 더 받지만 시작 하루가 안 지나 굳히지 않는다.
        // 같은 실행의 채우기 단계는 굳힐 때가 아닌 상세를 또 받지 않는다
        client.forget();
        clock.set(Instant.parse("2026-08-11T02:30:00Z"));
        client.use(data(final7020(), Map.of(7020L, box(4, 2, 3, 5))));
        ingestService.catchUp(true);
        assertThat(client.playByPlayAsked).containsExactly(7020L);
        assertThat(client.contentAsked).containsExactly(7020L);
        assertThat(saved(KIM, 7020).isComplete()).isFalse();
        // 경기 갱신이 없는 실행(채우기만)도 아직 받지 않는다
        client.forget();
        clock.set(Instant.parse("2026-08-11T05:00:00Z"));
        ingestService.catchUp(false);
        assertThat(client.playByPlayAsked).isEmpty();

        // 8/12 00:00Z(시작 하루 뒤): 채우기 단계가 굳힐 때가 된 상세를 다시 받아 굳힌다
        clock.set(Instant.parse("2026-08-12T00:00:00Z"));
        IngestLog filled = ingestService.catchUp(false);
        assertThat(client.playByPlayAsked).containsExactly(7020L);
        assertThat(client.contentAsked).containsExactly(7020L);
        assertThat(filled.getMessage()).contains("경기 상세 1개(경기 1개)");
        assertThat(saved(KIM, 7020).isComplete()).isTrue();

        // 8/13 00:00Z: 끝난 경기 재확인으로 박스스코어는 다시 받지만(3일까지 하루 한 번) 굳은 상세는 다시 받지 않는다
        client.forget();
        client.takeBoxscoresAsked();
        clock.set(Instant.parse("2026-08-13T00:00:00Z"));
        ingestService.catchUp(true);
        assertThat(client.takeBoxscoresAsked()).containsExactly(7020L);
        assertThat(client.playByPlayAsked).isEmpty();
        assertThat(client.contentAsked).isEmpty();
    }

    private IngestLog lastLog() {
        return ingestLogs.findAll().getLast();
    }

    private PlayerGameDetail saved(long playerId, long gamePk) {
        return details.findByPlayerIdAndGamePk(playerId, gamePk).orElseThrow();
    }

    private GameDetail detail(long playerId, long gamePk) {
        return json.readValue(saved(playerId, gamePk).getPayload(), GameDetail.class);
    }

    private static List<Long> descending(long from, long to) {
        return LongStream.rangeClosed(to, from).map(pk -> from + to - pk).boxed().toList();
    }

    // 7001(7/1)~7012(7/12): 끝난 지 오래된 경기
    private static List<MlbScheduleGame> pastGames() {
        return LongStream.rangeClosed(1, 12)
                .mapToObj(day -> game(7000 + day, NYM, ATL, String.format("2026-07-%02dT23:10:00Z", day), "R"))
                .toList();
    }

    private static MlbScheduleGame scheduled7020() {
        return game(7020, NYM, ATL, "2026-08-10T23:10:00Z", "R", "Preview", "S", "Scheduled", null, null);
    }

    private static MlbScheduleGame live7020() {
        return game(7020, NYM, ATL, "2026-08-10T23:10:00Z", "R", "Live", "I", "In Progress", 1, 0);
    }

    private static MlbScheduleGame final7020() {
        return game(7020, NYM, ATL, "2026-08-10T23:10:00Z", "R", "Final", "F", "Final", 3, 5);
    }

    private static MlbBoxscore box(int atBats, int hits, int awayRuns, int homeRuns) {
        return boxscore(NYM, ATL, true, 673490, "600", hittingStat(atBats, hits), null, List.of(1L), awayRuns,
                homeRuns);
    }

    private static SampleMlbClient.Data data(MlbScheduleGame game7020, Map<Long, MlbBoxscore> boxscores) {
        List<MlbScheduleGame> past = pastGames();
        List<MlbScheduleGame> schedule = new ArrayList<>(past);
        schedule.add(game7020);
        List<MlbGameLogSplit> logs = new ArrayList<>(past.stream().map(g -> batting(673490, ATL, g, 3, 1)).toList());
        logs.add(batting(808982, NYM, past.getLast(), 4, 1));
        return SampleMlbClient.Data.empty()
                .withPeople(List.of(hitter(673490, "Ha-Seong Kim", ATL), hitter(808982, "Jung Hoo Lee", NYM)))
                .withSchedule(schedule)
                .withHittingLogs(logs)
                .withBoxscores(boxscores);
    }
}
