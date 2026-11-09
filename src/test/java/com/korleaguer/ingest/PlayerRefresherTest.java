package com.korleaguer.ingest;

import com.korleaguer.client.MlbApiException;
import com.korleaguer.client.MlbBlockedException;
import com.korleaguer.client.MlbPerson;
import com.korleaguer.client.MlbTeam;
import com.korleaguer.client.MlbTransaction;
import com.korleaguer.client.SampleMlbClient;
import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.domain.Level;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 선수 정보·이동 기록 새로 받기: 팀 단계, 하루 한 번, 403/429 중단 */
class PlayerRefresherTest {

    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final MlbTeam TWINS = new MlbTeam(142L, "Minnesota Twins", null, null, null);
    private static final MlbTeam SAINTS = new MlbTeam(1960L, "St. Paul Saints", null, null, 142L);
    private static final KorleaguerProperties PROPERTIES = new KorleaguerProperties(2026, null, null, null, null, null);

    private final PlayerRepository players = mock(PlayerRepository.class);
    private final IngestWriter writer = mock(IngestWriter.class);
    private final Player go = player(5L, "고우석", 808970L);
    private final Player lee = player(1L, "이정후", 808982L);
    private final List<Long> askedTeams = new ArrayList<>();
    private final List<LocalDate[]> askedRanges = new ArrayList<>();
    private final List<Collection<Long>> askedPeople = new ArrayList<>();

    /** 질문을 적어 두는 샘플 클라이언트. failTeams의 팀은 5xx, blockPeople이면 선수 정보에서 403을 흉내 낸다. */
    private Set<Long> failTeams = Set.of();
    private boolean blockPeople;
    private final SampleMlbClient client = new SampleMlbClient(SampleMlbClient.Data.empty()
            .withPeople(List.of(MlbPerson.of(808970L, "Woo-Suk Go", SAINTS),
                    MlbPerson.of(808982L, "Jung Hoo Lee", new MlbTeam(137L, "San Francisco Giants", null, null, null))))
            .withTeams(List.of(team(1960L, 11L), team(1171L, 51L), team(4124L, 12L)))
            .withTransactions(List.of(move(1L, "2026-07-21", "OPT", TWINS, SAINTS),
                    move(2L, "2026-04-04", "SC", null, new MlbTeam(4124L, "Pensacola Blue Wahoos", null, null, null)),
                    move(3L, "2026-02-05", "SC", null, new MlbTeam(1171L, "Korea", null, null, null))))) {

        @Override
        public List<MlbPerson> getPeople(Collection<Long> personIds) {
            askedPeople.add(personIds);
            List<MlbPerson> people = super.getPeople(personIds);
            if (blockPeople) {
                throw new MlbBlockedException("403", null);
            }
            return people;
        }

        @Override
        public List<MlbTransaction> getTransactions(long personId, LocalDate startDate, LocalDate endDate) {
            askedRanges.add(new LocalDate[]{startDate, endDate});
            return super.getTransactions(personId, startDate, endDate);
        }

        @Override
        public MlbTeam getTeam(long teamId) {
            askedTeams.add(teamId);
            MlbTeam team = super.getTeam(teamId);
            if (failTeams.contains(teamId)) {
                throw new MlbApiException("502", null);
            }
            return team;
        }
    };

    private PlayerRefresher refresher;

    @BeforeEach
    void setUp() {
        when(players.findByMlbIdIsNotNull()).thenReturn(List.of(lee, go));
        refresher = new PlayerRefresher(client, players, writer, PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void levelFromTeamSportId() {
        assertThat(refresher.levelOf(142L)).isEqualTo(Level.MLB);      // MLB 구단은 묻지 않는다
        assertThat(refresher.levelOf(1960L)).isEqualTo(Level.MINORS);  // sport 11 = 트리플A
        assertThat(refresher.levelOf(4124L)).isEqualTo(Level.MINORS);  // sport 12 = 더블A
        assertThat(refresher.levelOf(1171L)).isNull();                 // 국가대표(sport 51)
        assertThat(refresher.levelOf(9999L)).isNull();                 // 없는 팀
        assertThat(refresher.levelOf(null)).isNull();
        assertThat(askedTeams).containsExactly(1960L, 4124L, 1171L, 9999L);

        // 한 번 물은 팀은 기억한다(모르는 팀도).
        refresher.levelOf(1960L);
        refresher.levelOf(1171L);
        refresher.levelOf(9999L);
        assertThat(askedTeams).hasSize(4);
    }

    @Test
    void failedTeamLookupIsNotRemembered() {
        failTeams = Set.of(1960L);
        assertThat(refresher.levelOf(1960L)).isNull();

        failTeams = Set.of();
        assertThat(refresher.levelOf(1960L)).isEqualTo(Level.MINORS);
        assertThat(askedTeams).containsExactly(1960L, 1960L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void savesPeopleInOneRequestWithMovesAndTeamLevels() {
        PlayerRefresher.Result result = refresher.refresh(false);

        assertThat(result.checked()).isEqualTo(2);
        assertThat(result.blocked()).isFalse();
        // 선수 정보는 한 번에, 이동 기록은 선수마다
        assertThat(askedPeople).singleElement().satisfies(ids -> assertThat(ids).containsExactly(808982L, 808970L));
        // 지난 시즌 1월 1일 ~ 이번 시즌 12월 31일
        assertThat(askedRanges).hasSize(2);
        assertThat(askedRanges.getFirst()).containsExactly(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 12, 31));
        ArgumentCaptor<Map<Long, Level>> levels = ArgumentCaptor.forClass(Map.class);
        verify(writer).savePlayer(eq(5L), any(), eq(Level.MINORS), eq(NOW), any(), levels.capture(), eq(TODAY));
        assertThat(levels.getValue()).containsOnly(Map.entry(142L, Level.MLB), Map.entry(1960L, Level.MINORS),
                Map.entry(4124L, Level.MINORS));
        verify(writer).savePlayer(eq(1L), any(), eq(Level.MLB), eq(NOW), any(), any(), eq(TODAY));
        assertThat(result.describe()).isEqualTo("선수 정보·이동 기록 2명");
    }

    @Test
    void checkedTodayIsSkippedUnlessForced() {
        go.updateLevel(Level.MINORS, "St. Paul Saints", NOW.minusSeconds(3600));                  // 오늘(UTC) 확인함
        lee.updateLevel(Level.MLB, "San Francisco Giants", Instant.parse("2026-11-09T23:00:00Z")); // 어제

        PlayerRefresher.Result result = refresher.refresh(false);

        assertThat(result.checked()).isEqualTo(1);
        assertThat(askedPeople).singleElement().satisfies(ids -> assertThat(ids).containsExactly(808982L));
        verify(writer, never()).savePlayer(eq(5L), any(), any(), any(), any(), any(), any());

        assertThat(refresher.refresh(true).checked()).isEqualTo(2);
    }

    @Test
    void nothingToDoMakesNoRequest() {
        go.updateLevel(Level.MINORS, "St. Paul Saints", NOW);
        lee.updateLevel(Level.MLB, "San Francisco Giants", NOW);

        PlayerRefresher.Result result = refresher.refresh(false);

        assertThat(client.requestCount()).isZero();
        assertThat(result.describe()).isEmpty();
    }

    @Test
    void blockedStopsTheRun() {
        blockPeople = true;

        PlayerRefresher.Result result = refresher.refresh(false);

        assertThat(result.blocked()).isTrue();
        assertThat(result.checked()).isZero();
        assertThat(client.requestCount()).isEqualTo(1); // 이동 기록은 묻지 않는다
        verify(writer, never()).savePlayer(anyLong(), any(), any(), any(), any(), any(), any());
        assertThat(result.describe()).contains("중단");
    }

    @Test
    void unknownPersonIsSkippedAndOthersContinue() {
        Player stranger = player(7L, "모르는선수", 111111L);
        when(players.findByMlbIdIsNotNull()).thenReturn(List.of(stranger, go));

        PlayerRefresher.Result result = refresher.refresh(false);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.checked()).isEqualTo(1);
        assertThat(result.describe()).contains("실패 1명");
        // 실패한 선수는 확인 시각을 남기지 않는다(다음 수집 때 다시 묻는다).
        verify(writer, never()).savePlayer(eq(7L), any(), any(), any(), any(), any(), any());
    }

    private static Player player(long id, String nameKo, long mlbId) {
        Player player = new Player(nameKo);
        ReflectionTestUtils.setField(player, "id", id);
        player.changeMlbId(mlbId);
        return player;
    }

    private static MlbTeam team(long id, long sportId) {
        return new MlbTeam(id, "Team " + id, null, new MlbTeam.Sport(sportId, null), null);
    }

    private static MlbTransaction move(long id, String date, String typeCode, MlbTeam from, MlbTeam to) {
        return new MlbTransaction(id, MlbPerson.of(808970L, "Woo-Suk Go", null), date, date, typeCode, typeCode,
                typeCode + " move", from, to);
    }
}
