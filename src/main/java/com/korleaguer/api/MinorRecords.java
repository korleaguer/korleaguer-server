package com.korleaguer.api;

import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.Batting;
import com.korleaguer.domain.MinorGameLog;
import com.korleaguer.domain.Pitching;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.Role;
import com.korleaguer.ingest.GameIngest;
import com.korleaguer.support.BaseballMath;
import com.korleaguer.support.GameSummary;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * '내 선수' 카드에 쓰는 마이너 기록 계산과, 메이저 출전·마이너 경기를 소식 계산용 숫자(GameStat)로 바꾸기(이슈 #33).
 * 마이너 경기는 시작 시각을 모르고 공식 날짜(미국 날짜)만 있어서, 한국 날짜로는 다음 날로 본다
 * (미국 저녁 경기는 한국 다음 날 아침에 끝난다).
 */
final class MinorRecords {

    /** 단계 약어 → 화면 이름 */
    static final Map<String, String> LEVEL_KO = Map.of(
            "AAA", "트리플A", "AA", "더블A", "A+", "하이A", "A", "싱글A", "ROK", "루키");
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    // 마이너 경기의 한국 시각을 어림할 때 쓰는 시각(미국 저녁 경기 = 한국 다음 날 아침)
    private static final LocalTime ASSUMED_KST_TIME = LocalTime.of(8, 0);

    private MinorRecords() {
    }

    /** 단계 약어의 화면 이름. 모르면 "마이너" */
    static String levelKo(String level) {
        return level == null ? "마이너" : LEVEL_KO.getOrDefault(level, "마이너");
    }

    /**
     * 여러 마이너 경기를 더한 기록의 단계 이름. 모두 한 단계면 그 단계(트리플A), 단계가 섞였으면 "마이너"
     * (더블A·트리플A를 더한 평균자책점에 '트리플A'를 붙이면 트리플A 기록으로 읽힌다, 이슈 #134).
     */
    static String levelKo(List<MinorGameLog> games) {
        List<String> levels = games.stream().map(MinorGameLog::getLevel).distinct().toList();
        return levels.size() == 1 ? levelKo(levels.getFirst()) : "마이너";
    }

    /** 마이너 경기의 한국 날짜(공식 날짜 다음 날) */
    static LocalDate kstDate(MinorGameLog game) {
        return game.getGameDate().plusDays(1);
    }

    /** 메이저 출전 한 번 → 소식 계산용 숫자. 날짜는 경기 시작의 미국 날짜다. */
    static FormFacts.GameStat stat(Appearance appearance) {
        return stat(LocalDate.ofInstant(appearance.getGame().getStartTimeUtc(), GameIngest.US_EASTERN),
                appearance.getBatting(), appearance.getPitching());
    }

    /** 마이너 경기 한 번 → 소식 계산용 숫자 */
    static FormFacts.GameStat stat(MinorGameLog game) {
        return stat(game.getGameDate(), game.getBatting(), game.getPitching());
    }

    private static FormFacts.GameStat stat(LocalDate date, Batting b, Pitching p) {
        return new FormFacts.GameStat(date, b != null, b == null ? 0 : orZero(b.atBats()), b == null ? 0 : orZero(b.hits()),
                b == null ? 0 : orZero(b.homeRuns()), b == null ? 0 : b.plateAppearancesOrComputed(), p != null,
                p == null ? null : p.runsAllowed());
    }

    /** 기간 타격 합계(마이너). seasonAvg는 그 시즌 마이너 전체 타율이다. */
    static MyPlayersResponse.PeriodHitting hitting(List<MinorGameLog> period, List<MinorGameLog> season) {
        List<Batting> lines = battings(period);
        List<Batting> all = battings(season);
        int atBats = sum(lines, Batting::atBats);
        int hits = sum(lines, Batting::hits);
        return new MyPlayersResponse.PeriodHitting(lines.size(), atBats, hits, sum(lines, Batting::homeRuns),
                sum(lines, Batting::rbi), sum(lines, Batting::walks), sum(lines, Batting::stolenBases),
                BaseballMath.avg(hits, atBats), BaseballMath.avg(sum(all, Batting::hits), sum(all, Batting::atBats)));
    }

    /** 기간 투구 합계(마이너). 평균자책점은 아웃 수를 아는 경기만 센다. seasonEra는 그 시즌 마이너 전체다. */
    static MyPlayersResponse.PeriodPitching pitching(List<MinorGameLog> period, List<MinorGameLog> season) {
        List<Pitching> lines = pitchings(period);
        List<Pitching> known = lines.stream().filter(p -> p.pitchingOuts() != null).toList();
        List<Pitching> allKnown = pitchings(season).stream().filter(p -> p.pitchingOuts() != null).toList();
        int outs = sumP(known, Pitching::pitchingOuts);
        int earnedRuns = sumP(known, Pitching::earnedRuns);
        return new MyPlayersResponse.PeriodPitching(lines.size(), outs, BaseballMath.inningsText(outs), earnedRuns,
                BaseballMath.era(earnedRuns, outs), sumP(lines, Pitching::pitcherStrikeouts), sumP(lines, Pitching::wins),
                sumP(lines, Pitching::losses), sumP(lines, Pitching::saves),
                BaseballMath.era(sumP(allKnown, Pitching::earnedRuns), sumP(allKnown, Pitching::pitchingOuts)));
    }

    /**
     * 기간의 마이너 경기 한 줄 기록(최신순). 팀·상대는 마이너 팀 이름(영어), 점수는 몰라서 null이고 결과(W/L)만 있다.
     */
    static List<GameLine> lines(Player player, String photoUrl, List<MinorGameLog> period) {
        Role role = player.role();
        return period.stream()
                .sorted(Comparator.comparing(MinorGameLog::getGameDate).thenComparing(MinorGameLog::getGamePk).reversed())
                .map(game -> new GameLine(player.getId(), player.getNameKo(), photoUrl, role, game.getGamePk(),
                        OffsetDateTime.of(kstDate(game), ASSUMED_KST_TIME, KST), game.getTeamName(), null,
                        game.getOpponentName(), null, Boolean.TRUE.equals(game.getHome()), null, null,
                        game.getWin() == null ? null : game.getWin() ? "W" : "L", "final", true,
                        GameSummary.played(role, game.getBatting(), game.getPitching()),
                        GameLineAssembler.batting(game.getBatting()), GameLineAssembler.pitching(game.getPitching()), null))
                .toList();
    }

    /** 한 시즌 메이저 합계(정규시즌). 출전이 없으면 null */
    static MyPlayersResponse.Totals majors(Player player, List<Appearance> regular) {
        if (regular.isEmpty()) {
            return null;
        }
        if (player.role() == Role.PITCHER) {
            PitcherSeason p = SeasonStats.pitcher(player, null, null, regular, 0);
            return new MyPlayersResponse.Totals("MLB", p.games(), 0, 0, 0, null, p.outs(), p.inningsText(), p.era(),
                    p.strikeouts(), p.saves(), 0);
        }
        HitterSeason h = SeasonStats.hitter(player, null, null, regular, 0);
        return new MyPlayersResponse.Totals("MLB", h.games(), h.atBats(), h.hits(), h.homeRuns(), h.avg(), 0, null,
                null, 0, 0, 0);
    }

    /** 한 시즌 마이너 합계. 경기가 없으면 null. 여러 단계를 뛰었으면 level은 MINORS다. */
    static MyPlayersResponse.Totals minors(Player player, List<MinorGameLog> season) {
        if (season.isEmpty()) {
            return null;
        }
        List<String> levels = season.stream().map(MinorGameLog::getLevel).filter(Objects::nonNull).distinct().toList();
        String level = levels.size() == 1 ? levels.getFirst() : "MINORS";
        if (player.role() == Role.PITCHER) {
            MyPlayersResponse.PeriodPitching p = pitching(season, season);
            int holds = season.stream().map(MinorGameLog::getHolds).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
            return new MyPlayersResponse.Totals(level, p.games(), 0, 0, 0, null, p.outs(), p.inningsText(), p.era(),
                    p.strikeouts(), p.saves(), holds);
        }
        MyPlayersResponse.PeriodHitting h = hitting(season, season);
        return new MyPlayersResponse.Totals(level, h.games(), h.atBats(), h.hits(), h.homeRuns(), h.avg(), 0, null,
                null, 0, 0, 0);
    }

    private static List<Batting> battings(List<MinorGameLog> games) {
        return games.stream().map(MinorGameLog::getBatting).filter(Objects::nonNull).toList();
    }

    private static List<Pitching> pitchings(List<MinorGameLog> games) {
        return games.stream().map(MinorGameLog::getPitching).filter(Objects::nonNull).toList();
    }

    private static int sum(List<Batting> lines, Function<Batting, Integer> field) {
        return lines.stream().map(field).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
    }

    private static int sumP(List<Pitching> lines, Function<Pitching, Integer> field) {
        return lines.stream().map(field).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
