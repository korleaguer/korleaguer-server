package com.korleaguer.api;

import com.korleaguer.domain.KoreanMlbSeason;
import com.korleaguer.domain.PlayerSeasonMetric;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 한국인 메이저리거 역대 기록 계산(GET /api/records, 이슈 #177). DB를 모르는 순수 계산이라 따로 시험한다.
 * 통산 값은 저장한 MLB 시즌별 기록(yearByYear)의 합이다. 이번 시즌 '넘은 선배'는 지난 시즌까지의 합에
 * 이번 시즌 경기별 기록(우리가 받은 메이저 정규시즌 경기)을 차례로 더하며 찾는다.
 */
final class KoreanRecords {

    private KoreanRecords() {
    }

    /** 순위를 매기는 기록. singleSeason이면 한 시즌 최다도 낸다 */
    enum Stat {
        HITS("hits", "안타", PlayerSeasonMetric.Group.HITTING, true, KoreanMlbSeason.Counts::hits),
        HOME_RUNS("homeRuns", "홈런", PlayerSeasonMetric.Group.HITTING, true, KoreanMlbSeason.Counts::homeRuns),
        GAMES("games", "경기", PlayerSeasonMetric.Group.HITTING, false, KoreanMlbSeason.Counts::games),
        STOLEN_BASES("stolenBases", "도루", PlayerSeasonMetric.Group.HITTING, true, KoreanMlbSeason.Counts::stolenBases),
        RBI("rbi", "타점", PlayerSeasonMetric.Group.HITTING, false, KoreanMlbSeason.Counts::rbi),
        WINS("wins", "승", PlayerSeasonMetric.Group.PITCHING, true, KoreanMlbSeason.Counts::wins),
        STRIKEOUTS("strikeouts", "탈삼진", PlayerSeasonMetric.Group.PITCHING, true, KoreanMlbSeason.Counts::strikeouts),
        SAVES("saves", "세이브", PlayerSeasonMetric.Group.PITCHING, true, KoreanMlbSeason.Counts::saves);

        final String key;
        /** 화면 이름이자 숫자 뒤에 붙이는 말(336안타, 124승) */
        final String label;
        final PlayerSeasonMetric.Group group;
        final boolean singleSeason;
        private final Function<KoreanMlbSeason.Counts, Integer> value;

        Stat(String key, String label, PlayerSeasonMetric.Group group, boolean singleSeason,
             Function<KoreanMlbSeason.Counts, Integer> value) {
            this.key = key;
            this.label = label;
            this.group = group;
            this.singleSeason = singleSeason;
            this.value = value;
        }

        static Stat valueOfKey(String key) {
            for (Stat stat : values()) {
                if (stat.key.equals(key)) {
                    return stat;
                }
            }
            throw new IllegalArgumentException(key);
        }

        int of(KoreanMlbSeason.Counts counts) {
            Integer v = counts == null ? null : value.apply(counts);
            return v == null ? 0 : v;
        }
    }

    /** 선수 한 명. playerId·photoUrl은 명단 선수만, active는 지금 명단 선수 중 국내로 돌아가지 않은 선수 */
    record Person(long mlbId, Long playerId, String nameKo, String photoUrl, PlayerSeasonMetric.Group group,
                  boolean active) {
    }

    /** 저장한 MLB 시즌 한 줄 */
    record SeasonLine(int season, KoreanMlbSeason.Counts counts) {
    }

    /** 이번 시즌 경기 한 줄(한국 날짜, 그 경기 개수 기록) */
    record GameLine(LocalDate dateKst, KoreanMlbSeason.Counts counts) {
    }

    /**
     * @param seasons     MLB 번호 → 시즌 줄(그 선수 묶음 것만)
     * @param seasonGames MLB 번호 → 이번 시즌 경기(오래된 것부터). 현역만 있으면 된다
     */
    static RecordsResponse build(String dataMode, int season, Instant now, List<Person> people,
                                 Map<Long, List<SeasonLine>> seasons, Map<Long, List<GameLine>> seasonGames) {
        Map<Long, Map<Stat, Integer>> totals = new HashMap<>();
        for (Person person : people) {
            Map<Stat, Integer> byStat = new HashMap<>();
            for (Stat stat : Stat.values()) {
                byStat.put(stat, seasons.getOrDefault(person.mlbId(), List.of()).stream()
                        .mapToInt(line -> stat.of(line.counts())).sum());
            }
            totals.put(person.mlbId(), byStat);
        }

        List<RecordsResponse.Category> categories = new ArrayList<>();
        Map<Stat, List<RecordsResponse.Leader>> leadersByStat = new HashMap<>();
        for (Stat stat : Stat.values()) {
            List<RecordsResponse.Leader> leaders = leaders(stat, people, seasons, totals);
            leadersByStat.put(stat, leaders);
            categories.add(new RecordsResponse.Category(stat.key, stat.label, stat.group.name(), stat.label, leaders));
        }

        List<RecordsResponse.Next> next = new ArrayList<>();
        for (Person person : people) {
            if (!person.active()) {
                continue;
            }
            for (Stat stat : Stat.values()) {
                if (stat.group != person.group()) {
                    continue;
                }
                RecordsResponse.Next one = next(stat, person, leadersByStat.get(stat), totals);
                if (one != null) {
                    next.add(one);
                }
            }
        }
        next.sort(Comparator.comparingDouble((RecordsResponse.Next n) -> (double) n.toPass() / n.targetValue())
                .thenComparingInt(RecordsResponse.Next::toPass));

        List<RecordsResponse.Passed> passed = passed(season, people, seasons, seasonGames, totals);

        List<RecordsResponse.Best> best = new ArrayList<>();
        for (Stat stat : Stat.values()) {
            if (stat.singleSeason) {
                best.add(new RecordsResponse.Best(stat.key, stat.label, stat.group.name(), stat.label,
                        singleSeason(stat, people, seasons)));
            }
        }
        return new RecordsResponse(dataMode, season, now, categories, next, passed, best);
    }

    /** 통산 순위(값 0은 뺀다). 같은 값이면 먼저 데뷔한 선수가 앞이고 등수는 같다 */
    private static List<RecordsResponse.Leader> leaders(Stat stat, List<Person> people,
                                                        Map<Long, List<SeasonLine>> seasons,
                                                        Map<Long, Map<Stat, Integer>> totals) {
        record Row(Person person, int value, int first, int last) {
        }
        List<Row> rows = new ArrayList<>();
        for (Person person : people) {
            int value = totals.get(person.mlbId()).get(stat);
            if (person.group() != stat.group || value <= 0) {
                continue;
            }
            List<SeasonLine> lines = seasons.getOrDefault(person.mlbId(), List.of());
            int first = lines.stream().mapToInt(SeasonLine::season).min().orElse(0);
            int last = lines.stream().mapToInt(SeasonLine::season).max().orElse(0);
            rows.add(new Row(person, value, first, last));
        }
        rows.sort(Comparator.comparingInt(Row::value).reversed().thenComparingInt(Row::first));
        List<RecordsResponse.Leader> result = new ArrayList<>();
        int rank = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (i == 0 || row.value() != rows.get(i - 1).value()) {
                rank = i + 1;
            }
            Person p = row.person();
            result.add(new RecordsResponse.Leader(rank, p.playerId(), p.mlbId(), p.nameKo(), p.photoUrl(), row.value(),
                    row.first(), row.last(), p.active()));
        }
        return result;
    }

    /** 바로 위 선수(값이 더 큰 선수 중 가장 작은 값, 같은 값이면 순위표에서 앞선 선수). 0이거나 1위면 없음 */
    private static RecordsResponse.Next next(Stat stat, Person person, List<RecordsResponse.Leader> leaders,
                                             Map<Long, Map<Stat, Integer>> totals) {
        int mine = totals.get(person.mlbId()).get(stat);
        if (mine <= 0) {
            return null;
        }
        RecordsResponse.Leader me = leaders.stream().filter(l -> l.mlbId() == person.mlbId()).findFirst().orElse(null);
        RecordsResponse.Leader target = null;
        for (RecordsResponse.Leader leader : leaders) {
            if (leader.value() > mine && (target == null || leader.value() < target.value())) {
                target = leader;
            }
        }
        if (me == null || target == null) {
            return null;
        }
        return new RecordsResponse.Next(person.playerId(), person.mlbId(), person.nameKo(), person.photoUrl(), stat.key,
                stat.label, stat.label, mine, me.rank(), target.nameKo(), target.value(), target.active(),
                target.value() - mine + 1, target.rank());
    }

    /**
     * 이번 시즌 넘은 선배: 현역 선수의 지난 시즌까지 합에서 시작해 이번 시즌 경기를 차례로 더하며,
     * 현역이 아닌 같은 묶음 선수의 통산 값(0보다 큰)을 처음 넘은 경기를 찾는다. 최근 것부터, 같은 날이면 기록 순서대로.
     */
    private static List<RecordsResponse.Passed> passed(int season, List<Person> people,
                                                       Map<Long, List<SeasonLine>> seasons,
                                                       Map<Long, List<GameLine>> seasonGames,
                                                       Map<Long, Map<Stat, Integer>> totals) {
        List<RecordsResponse.Passed> result = new ArrayList<>();
        for (Person person : people) {
            List<GameLine> games = seasonGames.getOrDefault(person.mlbId(), List.of());
            if (!person.active() || games.isEmpty()) {
                continue;
            }
            for (Stat stat : Stat.values()) {
                if (stat.group != person.group()) {
                    continue;
                }
                int cumulative = seasons.getOrDefault(person.mlbId(), List.of()).stream()
                        .filter(line -> line.season() < season)
                        .mapToInt(line -> stat.of(line.counts()))
                        .sum();
                for (GameLine game : games) {
                    int after = cumulative + stat.of(game.counts());
                    for (Person senior : people) {
                        if (senior.active() || senior.group() != person.group()) {
                            continue;
                        }
                        int value = totals.get(senior.mlbId()).get(stat);
                        if (value > 0 && cumulative <= value && value < after) {
                            result.add(new RecordsResponse.Passed(person.playerId(), person.mlbId(), person.nameKo(),
                                    person.photoUrl(), stat.key, stat.label, stat.label, game.dateKst(),
                                    senior.nameKo(), value));
                        }
                    }
                    cumulative = after;
                }
            }
        }
        result.sort(Comparator.comparing(RecordsResponse.Passed::dateKst).reversed()
                .thenComparingInt(p -> Stat.valueOfKey(p.category()).ordinal())
                .thenComparing(Comparator.comparingInt(RecordsResponse.Passed::passedValue).reversed()));
        return result;
    }

    /** 한 시즌 최다 10위까지(10위와 같은 값이면 함께) */
    private static List<RecordsResponse.SeasonRow> singleSeason(Stat stat, List<Person> people,
                                                                Map<Long, List<SeasonLine>> seasons) {
        record Row(Person person, int season, int value) {
        }
        List<Row> rows = new ArrayList<>();
        for (Person person : people) {
            if (person.group() != stat.group) {
                continue;
            }
            for (SeasonLine line : seasons.getOrDefault(person.mlbId(), List.of())) {
                int value = stat.of(line.counts());
                if (value > 0) {
                    rows.add(new Row(person, line.season(), value));
                }
            }
        }
        rows.sort(Comparator.comparingInt(Row::value).reversed().thenComparingInt(Row::season));
        List<RecordsResponse.SeasonRow> result = new ArrayList<>();
        int rank = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (i == 0 || row.value() != rows.get(i - 1).value()) {
                rank = i + 1;
            }
            if (rank > 10) {
                break;
            }
            Person p = row.person();
            result.add(new RecordsResponse.SeasonRow(rank, p.playerId(), p.mlbId(), p.nameKo(), row.season(),
                    row.value(), p.active()));
        }
        return result;
    }
}
