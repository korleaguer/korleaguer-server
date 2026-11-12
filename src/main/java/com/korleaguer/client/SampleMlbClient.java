package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 샘플 모드(테스트·오프라인)에서 쓰는 MLB 클라이언트. 네트워크를 쓰지 않는다.
 * resources/sample/mlb.json(MLB 응답과 같은 모양, 지어낸 값이며 실제 기록이 아니다)을 읽어 실제 API처럼 걸러서 돌려준다.
 * MLB 30개 구단은 팀 조회를 하지 않으므로 teams에는 마이너 팀만 둔다.
 */
public class SampleMlbClient implements MlbClient {

    private static final String SAMPLE_PATH = "sample/mlb.json";
    /** 샘플 데이터의 경기·기록이 있는 시즌. 샘플 모드는 시즌을 이 값으로 고정한다(CurrentSeason) */
    public static final int SEASON = 2026;

    private final Data data;
    private final AtomicLong requestCount = new AtomicLong();

    public SampleMlbClient(JsonMapper jsonMapper) {
        this(read(jsonMapper));
    }

    /** 데이터를 직접 넣는 생성자(테스트에서 상황을 만들 때 쓴다) */
    public SampleMlbClient(Data data) {
        this.data = data;
    }

    @Override
    public List<MlbPerson> getPeople(Collection<Long> personIds) {
        requestCount.incrementAndGet();
        return data.people().stream().filter(p -> personIds.contains(p.id())).toList();
    }

    @Override
    public List<MlbTransaction> getTransactions(long personId, LocalDate startDate, LocalDate endDate) {
        requestCount.incrementAndGet();
        return data.transactions().stream()
                .filter(t -> t.person() != null && Objects.equals(t.person().id(), personId))
                .filter(t -> within(t.date(), startDate, endDate))
                .toList();
    }

    @Override
    public MlbTeam getTeam(long teamId) {
        requestCount.incrementAndGet();
        return data.teams().stream().filter(t -> Objects.equals(t.id(), teamId)).findFirst().orElse(null);
    }

    @Override
    public List<MlbGameLogSplit> getGameLog(long personId, Group group, int season) {
        requestCount.incrementAndGet();
        List<MlbGameLogSplit> logs = group == Group.HITTING ? data.hittingLogs() : data.pitchingLogs();
        return logs.stream()
                .filter(s -> s.player() != null && Objects.equals(s.player().id(), personId))
                .filter(s -> String.valueOf(season).equals(s.season()))
                .toList();
    }

    @Override
    public MlbMinorGameLog getMinorGameLog(long personId, int season) {
        requestCount.incrementAndGet();
        MlbMinorGames counts = data.minorGames().get(personId);
        return counts == null ? MlbMinorGameLog.NONE : madeUpMinorLog(personId, season, counts, minorTeamOf(personId));
    }

    /** 샘플 선수의 지금 팀이 마이너 팀(트리플A ~ 루키)이면 그 팀, 아니면 null */
    private MlbTeam minorTeamOf(long personId) {
        return data.people().stream()
                .filter(p -> Objects.equals(p.id(), personId) && p.currentTeam() != null)
                .map(p -> data.teams().stream()
                        .filter(t -> Objects.equals(t.id(), p.currentTeam().id()))
                        .findFirst().orElse(null))
                .filter(t -> t != null && t.sportId() != null && MINOR_LEVELS.containsKey(t.sportId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 샘플 마이너 경기별 기록(지어낸 값). 경기 수(minorGames)만큼 그 시즌 9월 20일부터 하루씩 거슬러 올라가며 만든다.
     * 같은 번째 타격·투구 경기는 같은 경기(gamePk)다. 숫자는 순번으로 정해 늘 같은 값이 나온다.
     * 팀은 선수의 지금 마이너 팀이다(선수 정보와 맞아야 카드에 단계가 붙는다). 모르면 '샘플 트리플A 팀'.
     */
    static MlbMinorGameLog madeUpMinorLog(long personId, int season, MlbMinorGames counts, MlbTeam minorTeam) {
        MlbTeam team = minorTeam == null ? MADE_UP_MINOR_TEAM : minorTeam;
        MlbGameLogSplit.Sport sport = minorTeam == null ? new MlbGameLogSplit.Sport(11L, "AAA")
                : new MlbGameLogSplit.Sport(minorTeam.sportId(), MINOR_LEVELS.get(minorTeam.sportId()));
        LocalDate last = LocalDate.of(season, 9, 20);
        List<MlbGameLogSplit> hitting = new ArrayList<>();
        for (int i = 0; i < counts.hitting(); i++) {
            hitting.add(madeUpSplit(personId, season, last.minusDays(counts.hitting() - 1L - i), i, madeUpHitting(i),
                    team, sport));
        }
        List<MlbGameLogSplit> pitching = new ArrayList<>();
        for (int i = 0; i < counts.pitching(); i++) {
            pitching.add(madeUpSplit(personId, season, last.minusDays(counts.pitching() - 1L - i), i, madeUpPitching(i),
                    team, sport));
        }
        return new MlbMinorGameLog(hitting, pitching);
    }

    /** MLB sport.id → 마이너 단계 약어 */
    private static final Map<Long, String> MINOR_LEVELS = Map.of(11L, "AAA", 12L, "AA", 13L, "A+", 14L, "A", 16L, "ROK");

    private static final int[] MADE_UP_HITS = {1, 0, 2, 1, 0, 1, 3, 0, 1, 2};
    private static final MlbTeam MADE_UP_MINOR_TEAM = new MlbTeam(990001L, "샘플 트리플A 팀", null, null, null);
    private static final MlbTeam MADE_UP_OPPONENT = new MlbTeam(990002L, "샘플 상대 팀", null, null, null);

    private static MlbGameLogSplit madeUpSplit(long personId, int season, LocalDate date, int i, MlbStat stat,
                                               MlbTeam team, MlbGameLogSplit.Sport sport) {
        // 시즌·선수·순번으로 겹치지 않게 만든다(실제 MLB 번호와도 겹치지 않는 큰 수)
        long gamePk = 9_000_000_000L + (season % 100) * 100_000_000L + (personId % 100_000) * 1_000 + i;
        return new MlbGameLogSplit(String.valueOf(season), date.toString(), "R", i % 2 == 0, i % 3 != 1,
                team, MADE_UP_OPPONENT, new MlbGameLogSplit.GameRef(gamePk), MlbPerson.of(personId, null, null),
                stat, sport);
    }

    private static MlbStat madeUpHitting(int i) {
        int hits = MADE_UP_HITS[i % MADE_UP_HITS.length];
        int homeRuns = i % 7 == 3 && hits > 0 ? 1 : 0;
        int walks = i % 4 == 0 ? 1 : 0;
        int rbi = homeRuns + (i % 3 == 0 ? 1 : 0);
        int stolenBases = i % 9 == 5 ? 1 : 0;
        return new MlbStat(1, 1, 4, hits > 0 ? 1 : 0, hits, 0, 0, homeRuns, rbi, walks, 1, 0, stolenBases, 0, 4 + walks,
                hits + 3 * homeRuns, null, null, null, null, null, null, null, null, null);
    }

    private static MlbStat madeUpPitching(int i) {
        int outs = i % 5 == 2 ? 6 : 3;
        int runs = i % 5 == 4 ? 1 : 0;
        return new MlbStat(1, 0, null, runs, i % 3 == 0 ? 1 : 0, null, null, 0, null, i % 4 == 1 ? 1 : 0, 1 + i % 2,
                null, null, null, null, null, outs, null, runs, 15, 0, runs > 0 && i % 10 == 4 ? 1 : 0, i % 6 == 3 ? 1 : 0,
                i % 6 == 1 ? 1 : 0, 0);
    }

    @Override
    public List<MlbSeasonSplit> getYearByYear(long personId, Group group) {
        requestCount.incrementAndGet();
        return data.yearByYear().stream()
                .filter(s -> isOf(s, personId, group))
                .toList();
    }

    @Override
    public List<MlbSeasonSplit> getSabermetrics(long personId, Group group, int season) {
        requestCount.incrementAndGet();
        return data.sabermetrics().stream()
                .filter(s -> isOf(s, personId, group))
                .filter(s -> String.valueOf(season).equals(s.season()))
                .toList();
    }

    private static boolean isOf(MlbSeasonSplit split, long personId, Group group) {
        return split.player() != null && Objects.equals(split.player().id(), personId)
                && split.group() != null && group.value().equals(split.group().displayName());
    }

    @Override
    public List<MlbScheduleGame> getSeasonSchedule(Collection<Long> teamIds, int season) {
        requestCount.incrementAndGet();
        return data.schedule().stream()
                .filter(g -> involves(g, teamIds))
                .filter(g -> String.valueOf(season).equals(g.season()))
                .toList();
    }

    @Override
    public List<MlbScheduleGame> getSchedule(Collection<Long> teamIds, LocalDate startDate, LocalDate endDate) {
        requestCount.incrementAndGet();
        return data.schedule().stream()
                .filter(g -> involves(g, teamIds))
                .filter(g -> within(g.officialDate(), startDate, endDate))
                .toList();
    }

    @Override
    public MlbBoxscore getBoxscore(long gamePk) {
        requestCount.incrementAndGet();
        return data.boxscores().get(gamePk);
    }

    @Override
    public MlbSeason getSeason(int season) {
        requestCount.incrementAndGet();
        return data.seasons().stream()
                .filter(s -> String.valueOf(season).equals(s.seasonId()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public long requestCount() {
        return requestCount.get();
    }

    private static boolean involves(MlbScheduleGame game, Collection<Long> teamIds) {
        return game.teams() != null
                && (teamIds.contains(teamId(game.teams().away())) || teamIds.contains(teamId(game.teams().home())));
    }

    private static Long teamId(MlbScheduleGame.Side side) {
        return side == null || side.team() == null ? null : side.team().id();
    }

    private static boolean within(String date, LocalDate start, LocalDate end) {
        try {
            LocalDate day = LocalDate.parse(date);
            return !day.isBefore(start) && !day.isAfter(end);
        } catch (DateTimeParseException | NullPointerException e) {
            return false;
        }
    }

    private static Data read(JsonMapper jsonMapper) {
        try (InputStream in = new ClassPathResource(SAMPLE_PATH).getInputStream()) {
            return jsonMapper.readValue(in, Data.class).orEmpty();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + SAMPLE_PATH, e);
        }
    }

    /**
     * 샘플 데이터. 각 목록은 MLB 응답의 같은 이름 목록과 같은 모양이다.
     * schedule은 일정의 경기들, hittingLogs·pitchingLogs는 gameLog의 splits(선수 번호는 split.player.id),
     * boxscores는 gamePk → 박스스코어, minorGames는 선수 번호 → 그 시즌 마이너 출전 경기 수다(없으면 0경기).
     * 마이너 경기별 기록은 이 경기 수만큼 지어서 돌려준다(madeUpMinorLog).
     * seasons는 시즌 날짜(seasons/{year}의 seasons)다. 없는 해는 발표 전으로 본다(null).
     * yearByYear·sabermetrics는 시즌 합계·고급 지표 줄(MlbSeasonSplit)이고, split.player.id와 split.group으로 가른다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            List<MlbPerson> people,
            List<MlbTeam> teams,
            List<MlbTransaction> transactions,
            List<MlbScheduleGame> schedule,
            List<MlbGameLogSplit> hittingLogs,
            List<MlbGameLogSplit> pitchingLogs,
            Map<Long, MlbBoxscore> boxscores,
            Map<Long, MlbMinorGames> minorGames,
            List<MlbSeason> seasons,
            List<MlbSeasonSplit> yearByYear,
            List<MlbSeasonSplit> sabermetrics) {

        public static Data empty() {
            return new Data(null, null, null, null, null, null, null, null, null, null, null).orEmpty();
        }

        public Data withPeople(List<MlbPerson> people) {
            return new Data(people, teams, transactions, schedule, hittingLogs, pitchingLogs, boxscores, minorGames,
                    seasons, yearByYear, sabermetrics);
        }

        public Data withTeams(List<MlbTeam> teams) {
            return new Data(people, teams, transactions, schedule, hittingLogs, pitchingLogs, boxscores, minorGames,
                    seasons, yearByYear, sabermetrics);
        }

        public Data withTransactions(List<MlbTransaction> transactions) {
            return new Data(people, teams, transactions, schedule, hittingLogs, pitchingLogs, boxscores, minorGames,
                    seasons, yearByYear, sabermetrics);
        }

        public Data withSchedule(List<MlbScheduleGame> schedule) {
            return new Data(people, teams, transactions, schedule, hittingLogs, pitchingLogs, boxscores, minorGames,
                    seasons, yearByYear, sabermetrics);
        }

        public Data withHittingLogs(List<MlbGameLogSplit> hittingLogs) {
            return new Data(people, teams, transactions, schedule, hittingLogs, pitchingLogs, boxscores, minorGames,
                    seasons, yearByYear, sabermetrics);
        }

        public Data withPitchingLogs(List<MlbGameLogSplit> pitchingLogs) {
            return new Data(people, teams, transactions, schedule, hittingLogs, pitchingLogs, boxscores, minorGames,
                    seasons, yearByYear, sabermetrics);
        }

        public Data withBoxscores(Map<Long, MlbBoxscore> boxscores) {
            return new Data(people, teams, transactions, schedule, hittingLogs, pitchingLogs, boxscores, minorGames,
                    seasons, yearByYear, sabermetrics);
        }

        public Data withMinorGames(Map<Long, MlbMinorGames> minorGames) {
            return new Data(people, teams, transactions, schedule, hittingLogs, pitchingLogs, boxscores, minorGames,
                    seasons, yearByYear, sabermetrics);
        }

        public Data withSeasons(List<MlbSeason> seasons) {
            return new Data(people, teams, transactions, schedule, hittingLogs, pitchingLogs, boxscores, minorGames,
                    seasons, yearByYear, sabermetrics);
        }

        public Data withSeasonStats(List<MlbSeasonSplit> yearByYear, List<MlbSeasonSplit> sabermetrics) {
            return new Data(people, teams, transactions, schedule, hittingLogs, pitchingLogs, boxscores, minorGames,
                    seasons, yearByYear, sabermetrics);
        }

        private Data orEmpty() {
            return new Data(orEmpty(people), orEmpty(teams), orEmpty(transactions), orEmpty(schedule),
                    orEmpty(hittingLogs), orEmpty(pitchingLogs), boxscores == null ? Map.of() : boxscores,
                    minorGames == null ? Map.of() : minorGames, orEmpty(seasons), orEmpty(yearByYear),
                    orEmpty(sabermetrics));
        }

        private static <T> List<T> orEmpty(List<T> list) {
            return list == null ? List.of() : list;
        }
    }
}
