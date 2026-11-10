package com.korleaguer.api;

import com.korleaguer.domain.Lineup;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * '내 선수' 카드의 한 줄 소식과 출전 줄(이슈 #33). 판정('좋음'·'주춤')은 만들지 않고 일어난 사실만 쓴다.
 * 메이저 출전 기록과 마이너 기록을 같은 모양(GameStat)으로 받아 같은 규칙으로 센다.
 * <ul>
 *   <li>타자: 연속 안타 5경기 이상(HOT), 연속 무안타 4경기 이상(COLD, 2타석 이상 선 경기만 셈),
 *       최근 10경기 2홈런 이상·멀티히트 3번 이상(HOT). 연속 기록은 시즌 전체, 최근 경기는 지금 단계 기간에서 센다.</li>
 *   <li>투수: 연속 무실점 등판 3번 이상(HOT), 최근 5번 등판 중 무실점 4번 이상(HOT)·실점 3번 이상(COLD, 4번 이상 등판했을 때).</li>
 *   <li>비시즌·FA: 시즌 최장 연속 안타 5경기 이상 / 연속 무실점 등판 3번 이상.</li>
 * </ul>
 */
final class FormFacts {

    static final String HOT = "HOT";
    static final String COLD = "COLD";
    static final int RECENT_GAMES = 10;
    static final int RECENT_APPEARANCES = 5;
    private static final Map<CardStatuses.Start, String> AFTER = Map.of(
            CardStatuses.Start.RETURNED, "복귀 후 ",
            CardStatuses.Start.CALLED_UP, "콜업 후 ",
            CardStatuses.Start.SENT_DOWN, "강등 후 ");

    private FormFacts() {
    }

    /**
     * 경기 한 번 숫자. date는 미국 날짜, batted·pitched는 그 기록이 있는지, runsAllowed는 모르면 null이다.
     */
    record GameStat(LocalDate date, boolean batted, int atBats, int hits, int homeRuns, int plateAppearances,
                    boolean pitched, Integer runsAllowed) {
    }

    /**
     * @param season 시즌 경기(오래된 것부터, 연속 기록용)
     * @param stint  지금 단계 기간의 경기(오래된 것부터, 최근 경기용)
     * @param prefix 앞에 붙일 말("트리플A " 등, 없으면 "")
     * @param start  지금 단계 기간이 무엇으로 시작됐나(최근 경기가 다 차지 않으면 "복귀 후 6경기"처럼 쓴다)
     */
    static MyPlayersResponse.Fact hitter(List<GameStat> season, List<GameStat> stint, String prefix,
                                         CardStatuses.Start start) {
        int hitStreak = 0;
        for (GameStat game : season.reversed()) {
            if (!game.batted()) {
                continue;
            }
            if (game.hits() > 0) {
                hitStreak++;
            } else if (game.atBats() > 0) {
                break;
            }
        }
        if (hitStreak >= 5) {
            return new MyPlayersResponse.Fact(HOT, prefix + hitStreak + "경기 연속 안타");
        }
        int hitless = 0;
        for (GameStat game : season.reversed()) {
            if (!game.batted() || game.plateAppearances() < 2) {
                continue;
            }
            if (game.hits() > 0) {
                break;
            }
            hitless++;
        }
        if (hitless >= 4) {
            return new MyPlayersResponse.Fact(COLD, prefix + hitless + "경기 연속 무안타");
        }
        List<GameStat> batted = stint.stream().filter(GameStat::batted).toList();
        List<GameStat> recent = batted.subList(Math.max(0, batted.size() - RECENT_GAMES), batted.size());
        if (recent.isEmpty()) {
            return null;
        }
        String label = label(recent.size(), RECENT_GAMES, "경기", start);
        int homeRuns = recent.stream().mapToInt(GameStat::homeRuns).sum();
        if (homeRuns >= 2) {
            return new MyPlayersResponse.Fact(HOT, prefix + label + " " + homeRuns + "홈런");
        }
        long multiHit = recent.stream().filter(g -> g.hits() >= 2).count();
        if (multiHit >= 3) {
            return new MyPlayersResponse.Fact(HOT, prefix + label + " 멀티히트 " + multiHit + "번");
        }
        return null;
    }

    /** 투수 소식. 인자는 hitter와 같다. */
    static MyPlayersResponse.Fact pitcher(List<GameStat> season, List<GameStat> stint, String prefix,
                                          CardStatuses.Start start) {
        int scoreless = 0;
        for (GameStat game : season.reversed()) {
            if (!game.pitched()) {
                continue;
            }
            if (game.runsAllowed() == null || game.runsAllowed() > 0) {
                break;
            }
            scoreless++;
        }
        if (scoreless >= 3) {
            return new MyPlayersResponse.Fact(HOT, prefix + scoreless + "번 연속 무실점 등판");
        }
        List<GameStat> pitched = stint.stream().filter(GameStat::pitched).toList();
        List<GameStat> recent = pitched.subList(Math.max(0, pitched.size() - RECENT_APPEARANCES), pitched.size());
        if (recent.size() < 4) {
            return null;
        }
        String label = label(recent.size(), RECENT_APPEARANCES, "번 등판", start);
        long clean = recent.stream().filter(g -> g.runsAllowed() != null && g.runsAllowed() == 0).count();
        if (clean >= 4) {
            return new MyPlayersResponse.Fact(HOT, prefix + label + " 중 " + clean + "번 무실점");
        }
        long scored = recent.size() - clean;
        if (scored >= 3) {
            return new MyPlayersResponse.Fact(COLD, prefix + label + " 중 " + scored + "번 실점");
        }
        return null;
    }

    /** 비시즌·FA 소식: 시즌 최장 연속 기록(메이저·마이너 중 긴 쪽) */
    static MyPlayersResponse.Fact seasonBest(List<List<GameStat>> seasons, boolean pitcher) {
        int best = seasons.stream().mapToInt(games -> longest(games, pitcher)).max().orElse(0);
        if (pitcher) {
            return best >= 3 ? new MyPlayersResponse.Fact(HOT, "시즌 최장 " + best + "번 연속 무실점 등판") : null;
        }
        return best >= 5 ? new MyPlayersResponse.Fact(HOT, "시즌 최장 " + best + "경기 연속 안타") : null;
    }

    private static int longest(List<GameStat> games, boolean pitcher) {
        int best = 0;
        int current = 0;
        for (GameStat game : games) {
            if (pitcher) {
                if (!game.pitched()) {
                    continue;
                }
                current = game.runsAllowed() != null && game.runsAllowed() == 0 ? current + 1 : 0;
            } else {
                if (!game.batted() || (game.hits() == 0 && game.atBats() == 0)) {
                    continue;
                }
                current = game.hits() > 0 ? current + 1 : 0;
            }
            best = Math.max(best, current);
        }
        return best;
    }

    /** "최근 10경기" 또는 다 차지 않았으면 "복귀 후 6경기"(시작을 모르면 "최근 6경기") */
    private static String label(int count, int full, String unit, CardStatuses.Start start) {
        if (count >= full) {
            return "최근 " + full + unit;
        }
        return AFTER.getOrDefault(start, "최근 ") + count + unit;
    }

    /**
     * 타자 출전 줄. teamGames는 지금 단계 기간의 팀 끝난 경기(오래된 것부터, 최근 10개)다.
     * 뛰었으면 선발·교체(모르면 PLAYED), 안 뛰었으면 ABSENT.
     */
    static MyPlayersResponse.Play hitterPlay(List<GameLine> teamGames, CardStatuses.Start start) {
        List<String> recent = teamGames.stream()
                .map(line -> !line.played() ? "ABSENT"
                        : line.lineup() == Lineup.STARTER ? "STARTER"
                        : line.lineup() == Lineup.SUB ? "SUB" : "PLAYED")
                .toList();
        String after = recent.size() < RECENT_GAMES && (start == CardStatuses.Start.RETURNED
                || start == CardStatuses.Start.CALLED_UP) ? start.name() : null;
        return new MyPlayersResponse.Play(recent, after, null, null);
    }

    /** 투수 출전 줄: 최근 14일(오늘 포함) 등판 수와 마지막 등판이 며칠 전인지 */
    static MyPlayersResponse.Play pitcherPlay(List<GameStat> season, LocalDate usToday) {
        List<GameStat> pitched = season.stream().filter(GameStat::pitched).toList();
        int last14 = (int) pitched.stream()
                .filter(g -> !g.date().isAfter(usToday) && ChronoUnit.DAYS.between(g.date(), usToday) < 14)
                .count();
        Integer daysSince = pitched.isEmpty() ? null
                : (int) ChronoUnit.DAYS.between(pitched.getLast().date(), usToday);
        return new MyPlayersResponse.Play(null, null, last14, daysSince);
    }
}
