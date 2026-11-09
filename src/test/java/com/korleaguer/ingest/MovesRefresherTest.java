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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 선수 단계·이동 기록 새로 받기: 팀 단계, 하루 한 번, 403/429 중단 */
class MovesRefresherTest {

    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");
    private static final MlbTeam TWINS = new MlbTeam(142L, "Minnesota Twins", null, null, null);
    private static final MlbTeam SAINTS = new MlbTeam(1960L, "St. Paul Saints", null, null, 142L);

    private final PlayerRepository players = mock(PlayerRepository.class);
    private final IngestWriter writer = mock(IngestWriter.class);
    private final Player go = player(5L, "고우석");
    private final Player lee = player(1L, "이정후");
    private final List<Long> askedTeams = new ArrayList<>();
    private final List<LocalDate[]> askedRanges = new ArrayList<>();

    /** 팀·기간 질문을 적어 두는 샘플 클라이언트. failTeams의 팀은 5xx, blockPeople의 선수는 403을 흉내 낸다. */
    private Set<Long> failTeams = Set.of();
    private Set<Long> blockPeople = Set.of();
    private final SampleMlbClient client = new SampleMlbClient(
            List.of(new MlbPerson(808970L, "Woo-Suk Go", SAINTS),
                    new MlbPerson(808982L, "Jung Hoo Lee", new MlbTeam(137L, "San Francisco Giants", null, null, null))),
            List.of(team(1960L, 11L), team(1171L, 51L), team(4124L, 12L)),
            List.of(move(1L, "2026-07-21", "OPT", TWINS, SAINTS),
                    move(2L, "2026-04-04", "SC", null, new MlbTeam(4124L, "Pensacola Blue Wahoos", null, null, null)),
                    move(3L, "2026-02-05", "SC", null, new MlbTeam(1171L, "Korea", null, null, null)))) {

        @Override
        public MlbPerson getPerson(long personId) {
            if (blockPeople.contains(personId)) {
                super.getPerson(personId);
                throw new MlbBlockedException("403", null);
            }
            return super.getPerson(personId);
        }

        @Override
        public List<MlbTransaction> getTransactions(long personId, LocalDate startDate, LocalDate endDate) {
            askedRanges.add(new LocalDate[]{startDate, endDate});
            return super.getTransactions(personId, startDate, endDate);
        }

        @Override
        public MlbTeam getTeam(long teamId) {
            askedTeams.add(teamId);
            if (failTeams.contains(teamId)) {
                super.getTeam(teamId);
                throw new MlbApiException("502", null);
            }
            return super.getTeam(teamId);
        }
    };

    private MovesRefresher refresher;

    @BeforeEach
    void setUp() {
        RosterProperties roster = new RosterProperties(List.of(
                new RosterProperties.Entry("이정후", List.of("Jung Hoo Lee"), null, 808982L, null),
                new RosterProperties.Entry("고우석", List.of("Woo-Suk Go"), null, 808970L, null),
                new RosterProperties.Entry("명단만", List.of("Nobody"), null, null, null)));
        when(players.findByNameKo("이정후")).thenReturn(Optional.of(lee));
        when(players.findByNameKo("고우석")).thenReturn(Optional.of(go));
        KorleaguerProperties properties = new KorleaguerProperties(2026, null, null, null, null, null, null);
        refresher = new MovesRefresher(client, players, writer, roster, properties, Clock.fixed(NOW, ZoneOffset.UTC));
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
    void savesLevelTeamAndMovesWithTeamLevels() {
        MovesRefresher.Result result = refresher.refreshStale();

        assertThat(result.checked()).isEqualTo(2);
        assertThat(result.blocked()).isFalse();
        // 지난 시즌 1월 1일 ~ 이번 시즌 12월 31일
        assertThat(askedRanges.getFirst()).containsExactly(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 12, 31));
        ArgumentCaptor<Map<Long, Level>> levels = ArgumentCaptor.forClass(Map.class);
        verify(writer).saveLevelAndMoves(eq(5L), eq(Level.MINORS), eq("St. Paul Saints"), eq(NOW), any(),
                levels.capture());
        assertThat(levels.getValue()).containsOnly(Map.entry(142L, Level.MLB), Map.entry(1960L, Level.MINORS),
                Map.entry(4124L, Level.MINORS));
        verify(writer).saveLevelAndMoves(eq(1L), eq(Level.MLB), eq("San Francisco Giants"), eq(NOW), any(), any());
        assertThat(result.describe()).isEqualTo("MLB 단계·이동 기록 2명 (MLB 요청 " + result.requests() + "회)");
    }

    @Test
    void checkedTodayIsSkipped() {
        go.updateLevel(Level.MINORS, "St. Paul Saints", NOW.minusSeconds(3600));     // 오늘(UTC) 확인함
        lee.updateLevel(Level.MLB, "San Francisco Giants", Instant.parse("2026-11-09T23:00:00Z")); // 어제

        MovesRefresher.Result result = refresher.refreshStale();

        assertThat(result.checked()).isEqualTo(1);
        verify(writer, never()).saveLevelAndMoves(eq(5L), any(), any(), any(), any(), any());
        verify(writer).saveLevelAndMoves(eq(1L), any(), any(), any(), any(), any());
    }

    @Test
    void nothingToDoDescribesAsEmpty() {
        go.updateLevel(Level.MINORS, "St. Paul Saints", NOW);
        lee.updateLevel(Level.MLB, "San Francisco Giants", NOW);

        MovesRefresher.Result result = refresher.refreshStale();

        assertThat(result.requests()).isZero();
        assertThat(result.describe()).isEmpty();
    }

    @Test
    void blockedStopsTheRun() {
        blockPeople = Set.of(808982L); // 이정후(명단 첫 줄)에서 403

        MovesRefresher.Result result = refresher.refreshStale();

        assertThat(result.blocked()).isTrue();
        assertThat(result.checked()).isZero();
        assertThat(result.requests()).isEqualTo(1); // 고우석은 묻지 않는다
        verify(writer, never()).saveLevelAndMoves(anyLong(), any(), any(), any(), any(), any());
        assertThat(result.describe()).contains("중단");
    }

    @Test
    void unknownPersonIsSkippedAndOthersContinue() {
        SampleMlbClient onlyGo = new SampleMlbClient(List.of(new MlbPerson(808970L, "Woo-Suk Go", SAINTS)),
                List.of(team(1960L, 11L)), List.of());
        RosterProperties roster = new RosterProperties(List.of(
                new RosterProperties.Entry("이정후", List.of("Jung Hoo Lee"), null, 808982L, null),
                new RosterProperties.Entry("고우석", List.of("Woo-Suk Go"), null, 808970L, null)));
        MovesRefresher other = new MovesRefresher(onlyGo, players, writer, roster,
                new KorleaguerProperties(2026, null, null, null, null, null, null), Clock.fixed(NOW, ZoneOffset.UTC));

        MovesRefresher.Result result = other.refreshStale();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.checked()).isEqualTo(1);
        assertThat(result.describe()).contains("실패 1명");
        // 실패한 선수는 확인 시각을 남기지 않는다(다음 수집 때 다시 묻는다).
        verify(writer, never()).saveLevelAndMoves(eq(1L), any(), any(), any(), any(), any());
    }

    private static Player player(long id, String nameKo) {
        Player player = new Player(nameKo);
        ReflectionTestUtils.setField(player, "id", id);
        return player;
    }

    private static MlbTeam team(long id, long sportId) {
        return new MlbTeam(id, "Team " + id, null, new MlbTeam.Sport(sportId, null), null);
    }

    private static MlbTransaction move(long id, String date, String typeCode, MlbTeam from, MlbTeam to) {
        return new MlbTransaction(id, new MlbPerson(808970L, "Woo-Suk Go", null), date, date, typeCode, typeCode,
                typeCode + " move", from, to);
    }
}
