package com.korleaguer.api.season;

import com.korleaguer.api.game.GameBatting;
import com.korleaguer.api.game.GamePitching;

import com.korleaguer.api.game.GameLine;
import com.korleaguer.api.game.MinorGameLine;
import com.korleaguer.api.player.MinorTeamNames;

import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.Batting;
import com.korleaguer.domain.MinorGameLog;
import com.korleaguer.domain.MinorGameSchedule;
import com.korleaguer.domain.Pitching;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.Role;
import com.korleaguer.domain.SeasonType;
import com.korleaguer.support.BaseballMath;
import com.korleaguer.support.GameStatuses;
import com.korleaguer.support.GameSummary;
import com.korleaguer.support.KoreanTime;
import com.korleaguer.support.MlbTime;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * '내 선수' 카드에 쓰는 마이너 기록 계산과, 메이저 출전·마이너 경기를 소식 계산용 숫자(GameStat)로 바꾸기(이슈 #33).
 * 마이너 경기의 한국 시각·날짜는 어디서나 startTimeKst 하나로 정한다(선수 화면 경기 줄·달력, 내 선수 카드의 기간·경기 줄,
 * 마지막 경기 날짜, 이슈 #183): 일정에서 받은 시작 시각, 아직 받지 못했으면 공식 날짜(미국 날짜) 다음 날 08:00
 * (미국 저녁 경기는 한국 다음 날 아침이다).
 */
public final class MinorRecords {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    // 마이너 경기의 한국 시각을 어림할 때 쓰는 시각(미국 저녁 경기 = 한국 다음 날 아침)
    private static final LocalTime ASSUMED_KST_TIME = LocalTime.of(8, 0);
    // 승패가 아직 나지 않은 상태(경기 전·연기·취소·경기 중·지연·중단). 이때는 경기별 기록의 승패도 쓰지 않는다
    private static final Set<String> UNDECIDED = Set.of(GameStatuses.SCHEDULED, GameStatuses.POSTPONED,
            GameStatuses.CANCELED, GameStatuses.IN_PROGRESS, GameStatuses.DELAYED, GameStatuses.SUSPENDED);
    // 마이너 경기 줄의 순서(최신 경기부터): 공식 날짜, 같은 날(더블헤더)이면 시작 시각, 그다음 경기 번호
    private static final Comparator<MinorGameLog> NEWEST_FIRST = Comparator.comparing(MinorGameLog::getGameDate)
            .thenComparing(MinorRecords::startTimeKst)
            .thenComparing(MinorGameLog::getGamePk)
            .reversed();
    // 마이너 경기 줄의 경기 종류(이슈 #190). 마이너 경기 기록은 받을 때 정규시즌(gameType R) 줄만 남겨 저장하므로
    // (MlbMinorGameLog.isRegularSeason) 저장된 마이너 경기는 모두 정규시즌이다
    private static final SeasonType MINOR_SEASON_TYPE = SeasonType.REGULAR;

    private MinorRecords() {
    }

    /** 단계 약어의 화면 이름. 모르면 "마이너" */
    public static String levelKo(String level) {
        return level == null ? "마이너" : MinorTeamNames.LEVEL_KO.getOrDefault(level, "마이너");
    }

    /**
     * 여러 마이너 경기를 더한 기록의 단계 이름. 모두 한 단계면 그 단계(트리플A), 단계가 섞였으면 "마이너"
     * (더블A·트리플A를 더한 평균자책점에 '트리플A'를 붙이면 트리플A 기록으로 읽힌다, 이슈 #134).
     * 단계를 모르는 경기(level null)도 한 단계로 센다: 트리플A + 모름이면 "마이너", 모두 모르면 levelKo(null)로 "마이너".
     * 성적표 숫자 칸의 단계 이름(SeasonRates.levelKo, statLevelKo)도 같은 규칙이다(한쪽을 바꾸면 다른 쪽도 바꾼다).
     */
    public static String levelKo(List<MinorGameLog> games) {
        List<String> levels = games.stream().map(MinorGameLog::getLevel).distinct().toList();
        return levels.size() == 1 ? levelKo(levels.getFirst()) : "마이너";
    }

    /**
     * 마이너 경기의 한국 날짜: startTimeKst의 날짜(일정에서 받은 시작 시각, 못 받았으면 공식 날짜 다음 날). 예전에는 늘 공식
     * 날짜 다음 날로 셌는데, 선수 화면 달력은 받은 시작 시각으로 칸을 정해서 미국 오전 경기(동부 11시 전 시작)는 같은 경기가
     * 다른 날에 놓일 수 있었다(이슈 #183)
     */
    public static LocalDate kstDate(MinorGameLog game) {
        return startTimeKst(game).toLocalDate();
    }

    /** 메이저 출전 한 번 → 소식 계산용 숫자. 날짜는 경기 시작의 미국 날짜다. */
    public static GameStat stat(Appearance appearance) {
        return stat(LocalDate.ofInstant(appearance.getGame().getStartTimeUtc(), MlbTime.US_EASTERN),
                appearance.getBatting(), appearance.getPitching());
    }

    /** 마이너 경기 한 번 → 소식 계산용 숫자 */
    public static GameStat stat(MinorGameLog game) {
        return stat(game.getGameDate(), game.getBatting(), game.getPitching());
    }

    private static GameStat stat(LocalDate date, Batting b, Pitching p) {
        return new GameStat(date, b != null, b == null ? 0 : orZero(b.atBats()), b == null ? 0 : orZero(b.hits()),
                b == null ? 0 : orZero(b.homeRuns()), b == null ? 0 : b.plateAppearancesOrComputed(), p != null,
                p == null ? null : p.runsAllowed());
    }

    /** 기간 타격 합계(마이너). seasonAvg는 그 시즌 마이너 전체 타율이다. */
    public static PeriodHitting hitting(List<MinorGameLog> period, List<MinorGameLog> season,
                                                   java.util.function.Function<String, LeagueEnv> leagues) {
        List<Batting> lines = battings(period);
        List<Batting> all = battings(season);
        int atBats = sum(lines, Batting::atBats);
        int hits = sum(lines, Batting::hits);
        // 기간 wRC+는 그 단계 리그 평균과 견준다(한 단계일 때만, 아니면 null)
        String level = PeriodRates.singleLevel(period);
        Double wrc = level == null ? null : PeriodRates.wrcPlusOf(lines, leagues.apply(level));
        return new PeriodHitting(lines.size(), atBats, hits, sum(lines, Batting::homeRuns),
                sum(lines, Batting::rbi), sum(lines, Batting::walks), sum(lines, Batting::stolenBases),
                BaseballMath.avg(hits, atBats), BaseballMath.avg(sum(all, Batting::hits), sum(all, Batting::atBats)),
                wrc, wrc == null ? null : level, PeriodRates.mixedLevels(period));
    }

    /** 기간 투구 합계(마이너). 평균자책점은 아웃 수를 아는 경기만 센다. seasonEra는 그 시즌 마이너 전체다. */
    public static PeriodPitching pitching(List<MinorGameLog> period, List<MinorGameLog> season,
                                                     java.util.function.Function<String, LeagueEnv> leagues) {
        List<Pitching> lines = pitchings(period);
        List<Pitching> known = lines.stream().filter(p -> p.pitchingOuts() != null).toList();
        List<Pitching> allKnown = pitchings(season).stream().filter(p -> p.pitchingOuts() != null).toList();
        int outs = sumP(known, Pitching::pitchingOuts);
        int earnedRuns = sumP(known, Pitching::earnedRuns);
        String level = PeriodRates.singleLevel(period);
        Double eraPlus = level == null ? null : PeriodRates.eraPlusOf(lines, leagues.apply(level));
        return new PeriodPitching(lines.size(), outs, BaseballMath.inningsText(outs), earnedRuns,
                BaseballMath.era(earnedRuns, outs), sumP(lines, Pitching::pitcherStrikeouts), sumP(lines, Pitching::wins),
                sumP(lines, Pitching::losses), sumP(lines, Pitching::saves),
                BaseballMath.era(sumP(allKnown, Pitching::earnedRuns), sumP(allKnown, Pitching::pitchingOuts)),
                eraPlus, eraPlus == null ? null : level, PeriodRates.mixedLevels(period));
    }

    /**
     * 기간의 마이너 경기 한 줄 기록(최신순, 선수 화면 마이너 경기 줄과 같은 순서). 시작 시각은 선수 화면 마이너 경기 줄과 같은
     * startTimeKst다(같은 경기가 같은 날·시각, 이슈 #183). 팀·상대는 마이너 팀 이름(영어), 점수는 null이고 결과(W/L)만 있다.
     */
    public static List<GameLine> lines(Player player, String photoUrl, String photoTeamAbbr, List<MinorGameLog> period) {
        Role role = player.role();
        return period.stream()
                .sorted(NEWEST_FIRST)
                .map(game -> new GameLine(player.getId(), player.getNameKo(), photoUrl, role, game.getGamePk(),
                        startTimeKst(game), game.getTeamName(), null,
                        game.getOpponentName(), null, Boolean.TRUE.equals(game.getHome()), null, null,
                        game.getWin() == null ? null : game.getWin() ? "W" : "L", GameStatuses.FINAL, true,
                        GameSummary.played(role, game.getBatting(), game.getPitching()),
                        GameBatting.of(game.getBatting()), GamePitching.of(game.getPitching()), null,
                        MINOR_SEASON_TYPE, photoTeamAbbr))
                .toList();
    }

    /** 선수 화면의 마이너 경기 줄(MinorGameLine). 받은 경기를 모두, 최신 경기부터 */
    public static List<MinorGameLine> gameLines(Role role, List<MinorGameLog> season, MinorTeamNames names) {
        return season.stream()
                .sorted(NEWEST_FIRST)
                .map(game -> gameLine(role, game, names))
                .toList();
    }

    private static MinorGameLine gameLine(Role role, MinorGameLog game, MinorTeamNames names) {
        MinorGameSchedule schedule = game.getSchedule();
        boolean scored = schedule != null && schedule.statusState() != null
                && !GameStatuses.NOT_STARTED.contains(schedule.statusState());
        Integer teamScore = scored ? schedule.teamScore() : null;
        Integer opponentScore = scored ? schedule.opponentScore() : null;
        return new MinorGameLine(game.getGamePk(), startTimeKst(game), game.getLevel(), levelKo(game.getLevel()),
                game.getTeamId(), names.name(game.getTeamId(), schedule == null ? null : schedule.teamPlace(),
                        game.getTeamName()),
                game.getOpponentId(), names.name(game.getOpponentId(), schedule == null ? null : schedule.opponentPlace(),
                        game.getOpponentName()),
                game.getHome(), teamScore, opponentScore, result(game, schedule, teamScore, opponentScore),
                GameSummary.played(role, game.getBatting(), game.getPitching()),
                GameBatting.of(game.getBatting()), GamePitching.of(game.getPitching()),
                MINOR_SEASON_TYPE);
    }

    /**
     * 시작 시각(한국 시각). 선수 화면 마이너 경기 줄, 내 선수 카드의 마이너 경기 줄(lines)·기간(kstDate)·마지막 경기 날짜가
     * 모두 이 값을 쓴다(같은 경기는 어디서나 같은 날, 이슈 #183).
     * 일정을 아직 받지 못했으면(시작 시각 없이 공식 날짜만 있다) 공식 날짜 다음 날 08:00으로 어림한다. 마이너 경기는 거의
     * 미국 저녁 경기라 한국 다음 날 아침이다: 운영(11-18) 이번 시즌 266경기는 모두 일정을 받았고 모두 한국 날짜가 공식
     * 날짜 다음 날이었다. 어림이 틀려도 달력과 카드가 이 값 하나를 쓰므로 서로 어긋나지 않고, 일정은 다음 수집 때 다시 물어
     * 받으면 실제 시각으로 바뀐다(MinorGameWriter.saveMinorGameLog). kstDate를 부르면 서로 부르게 되므로 공식 날짜에서 바로 센다
     */
    public static OffsetDateTime startTimeKst(MinorGameLog game) {
        MinorGameSchedule schedule = game.getSchedule();
        return schedule != null && schedule.startTimeUtc() != null ? KoreanTime.toKst(schedule.startTimeUtc())
                : OffsetDateTime.of(game.getGameDate().plusDays(1), ASSUMED_KST_TIME, KST);
    }

    /**
     * 끝나 점수를 아는 경기는 점수로 W·L·T. 승패가 아직 나지 않은 상태(UNDECIDED)면 null.
     * 그 밖(일정을 모름, 상태를 모름, 끝났는데 점수가 없음)은 경기별 기록의 승패다.
     */
    private static String result(MinorGameLog game, MinorGameSchedule schedule, Integer teamScore,
                                 Integer opponentScore) {
        String state = schedule == null ? null : schedule.statusState();
        if (state != null && UNDECIDED.contains(state)) {
            return null;
        }
        if (GameStatuses.FINAL.equals(state) && teamScore != null && opponentScore != null) {
            int compared = Integer.compare(teamScore, opponentScore);
            return compared > 0 ? "W" : compared < 0 ? "L" : "T";
        }
        return game.getWin() == null ? null : game.getWin() ? "W" : "L";
    }

    /** 한 시즌 메이저 합계(정규시즌). 출전이 없으면 null */
    public static LevelTotals majors(Player player, List<Appearance> regular) {
        if (regular.isEmpty()) {
            return null;
        }
        if (player.role() == Role.PITCHER) {
            PitcherSeason p = SeasonStats.pitcher(player, null, null, null, regular, 0);
            return new LevelTotals("MLB", p.games(), 0, 0, 0, null, p.outs(), p.inningsText(), p.era(),
                    p.strikeouts(), p.saves(), 0);
        }
        HitterSeason h = SeasonStats.hitter(player, null, null, null, regular, 0);
        return new LevelTotals("MLB", h.games(), h.atBats(), h.hits(), h.homeRuns(), h.avg(), 0, null,
                null, 0, 0, 0);
    }

    /** 한 시즌 마이너 합계. 경기가 없으면 null. 여러 단계를 뛰었으면 level은 MINORS다. */
    public static LevelTotals minors(Player player, List<MinorGameLog> season) {
        if (season.isEmpty()) {
            return null;
        }
        List<String> levels = season.stream().map(MinorGameLog::getLevel).filter(Objects::nonNull).distinct().toList();
        String level = levels.size() == 1 ? levels.getFirst() : "MINORS";
        if (player.role() == Role.PITCHER) {
            PeriodPitching p = pitching(season, season, l -> null);
            int holds = season.stream().map(MinorGameLog::getHolds).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
            return new LevelTotals(level, p.games(), 0, 0, 0, null, p.outs(), p.inningsText(), p.era(),
                    p.strikeouts(), p.saves(), holds);
        }
        PeriodHitting h = hitting(season, season, l -> null);
        return new LevelTotals(level, h.games(), h.atBats(), h.hits(), h.homeRuns(), h.avg(), 0, null,
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
