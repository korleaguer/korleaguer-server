package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
    public MlbMinorGames getMinorGames(long personId, int season) {
        requestCount.incrementAndGet();
        return data.minorGames().getOrDefault(personId, MlbMinorGames.NONE);
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
     * seasons는 시즌 날짜(seasons/{year}의 seasons)다. 없는 해는 발표 전으로 본다(null).
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
            List<MlbSeason> seasons) {

        public static Data empty() {
            return new Data(null, null, null, null, null, null, null, null, null).orEmpty();
        }

        public Data withPeople(List<MlbPerson> people) {
            return new Data(people, teams, transactions, schedule, hittingLogs, pitchingLogs, boxscores, minorGames,
                    seasons);
        }

        public Data withTeams(List<MlbTeam> teams) {
            return new Data(people, teams, transactions, schedule, hittingLogs, pitchingLogs, boxscores, minorGames,
                    seasons);
        }

        public Data withTransactions(List<MlbTransaction> transactions) {
            return new Data(people, teams, transactions, schedule, hittingLogs, pitchingLogs, boxscores, minorGames,
                    seasons);
        }

        public Data withSchedule(List<MlbScheduleGame> schedule) {
            return new Data(people, teams, transactions, schedule, hittingLogs, pitchingLogs, boxscores, minorGames,
                    seasons);
        }

        public Data withHittingLogs(List<MlbGameLogSplit> hittingLogs) {
            return new Data(people, teams, transactions, schedule, hittingLogs, pitchingLogs, boxscores, minorGames,
                    seasons);
        }

        public Data withPitchingLogs(List<MlbGameLogSplit> pitchingLogs) {
            return new Data(people, teams, transactions, schedule, hittingLogs, pitchingLogs, boxscores, minorGames,
                    seasons);
        }

        public Data withBoxscores(Map<Long, MlbBoxscore> boxscores) {
            return new Data(people, teams, transactions, schedule, hittingLogs, pitchingLogs, boxscores, minorGames,
                    seasons);
        }

        public Data withMinorGames(Map<Long, MlbMinorGames> minorGames) {
            return new Data(people, teams, transactions, schedule, hittingLogs, pitchingLogs, boxscores, minorGames,
                    seasons);
        }

        public Data withSeasons(List<MlbSeason> seasons) {
            return new Data(people, teams, transactions, schedule, hittingLogs, pitchingLogs, boxscores, minorGames,
                    seasons);
        }

        private Data orEmpty() {
            return new Data(orEmpty(people), orEmpty(teams), orEmpty(transactions), orEmpty(schedule),
                    orEmpty(hittingLogs), orEmpty(pitchingLogs), boxscores == null ? Map.of() : boxscores,
                    minorGames == null ? Map.of() : minorGames, orEmpty(seasons));
        }

        private static <T> List<T> orEmpty(List<T> list) {
            return list == null ? List.of() : list;
        }
    }
}
