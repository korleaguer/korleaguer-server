package com.korleaguer.api.records;

import java.util.List;
import java.util.Optional;

/**
 * 한 경기가 화제 문구를 달 만한지 판단하는 규칙(저장소 없이 숫자만 본다). 경기 목록은 그 시즌 정규시즌 경기를 오래된 것부터
 * 그 경기까지 담는다(마지막이 지금 경기). 판단 거리는 재료 조사(plan/2026-11-21-화제-문구-재료.md)의 건수를 따른다.
 * '한국인 최초 ○○'는 기준표가 없어 만들지 않는다.
 */
final class GameBuzzRules {

    /** 연속 안타 문구는 5경기부터 5의 배수마다만 낸다(달성한 날) */
    static final int STREAK_STEP = 5;
    /** 시즌 최고 경기는 타격 기록이 있는 경기가 이만큼 쌓인 뒤부터 본다 */
    static final int BEST_AFTER_GAMES = 10;
    /** 시즌 최고 경기의 최소 점수. 이 아래는 시즌 최고여도 화제로 말하기엔 약하다(2026 실제: 김하성 3~4점) */
    static final double BEST_MIN_SCORE = 6;

    private GameBuzzRules() {
    }

    /**
     * 경기 한 번 숫자. batted·pitched는 그 기록이 있는지(대수비 등 기록 없는 출전은 둘 다 false).
     * totalBases는 루타, walks는 볼넷이다.
     */
    record Game(long gameId, boolean batted, int atBats, int hits, int homeRuns, int rbi, int walks, int stolenBases,
                int totalBases, boolean pitched) {

        /** 경기 점수: 루타 + 타점 + 도루 + 볼넷÷2. 숫자가 큰 경기가 더 좋은 경기다 */
        double score() {
            return totalBases + rbi + stolenBases + walks / 2.0;
        }
    }

    /**
     * 처음 → 연속 → 시즌 최고 순으로 첫 번째로 걸리는 문구. 넘은 기록은 시즌 기록만으로 알 수 없어 부르는 쪽에서 먼저 본다.
     *
     * @param season 그 시즌 정규시즌 경기, 오래된 것부터, 마지막이 지금 경기
     * @param pitcher 선수의 주 역할이 투수인가
     * @param debutSeason 이번 시즌이 메이저 첫 시즌인가(지난 시즌 메이저 기록이 없다)
     */
    static Optional<GameBuzz> find(long playerId, List<Game> season, boolean pitcher, boolean debutSeason) {
        if (season.isEmpty()) {
            return Optional.empty();
        }
        Game now = season.getLast();
        List<Game> before = season.subList(0, season.size() - 1);
        Optional<GameBuzz> first = first(playerId, now, before, pitcher, debutSeason);
        if (first.isPresent() || pitcher) {
            return first;
        }
        int streak = hitStreak(season);
        if (now.hits() > 0 && streak >= STREAK_STEP && streak % STREAK_STEP == 0) {
            return Optional.of(new GameBuzz(playerId, now.gameId(), GameBuzz.STREAK, null, streak + "경기 연속 안타"));
        }
        if (isSeasonBest(now, before)) {
            return Optional.of(new GameBuzz(playerId, now.gameId(), GameBuzz.SEASON_BEST, null, "올 시즌 최고 경기"));
        }
        return Optional.empty();
    }

    private static Optional<GameBuzz> first(long playerId, Game now, List<Game> before, boolean pitcher,
                                            boolean debutSeason) {
        if (pitcher) {
            boolean firstOuting = now.pitched() && before.stream().noneMatch(Game::pitched);
            return firstOuting && debutSeason
                    ? Optional.of(new GameBuzz(playerId, now.gameId(), GameBuzz.FIRST, null, "메이저 첫 등판"))
                    : Optional.empty();
        }
        if (now.homeRuns() > 0 && before.stream().noneMatch(g -> g.homeRuns() > 0)) {
            String text = debutSeason ? "메이저 첫 홈런" : "올 시즌 첫 홈런";
            return Optional.of(new GameBuzz(playerId, now.gameId(), GameBuzz.FIRST, "homeRuns", text));
        }
        if (debutSeason && now.hits() > 0 && before.stream().noneMatch(g -> g.hits() > 0)) {
            return Optional.of(new GameBuzz(playerId, now.gameId(), GameBuzz.FIRST, "hits", "메이저 첫 안타"));
        }
        return Optional.empty();
    }

    /** 지금 경기까지 이어진 연속 안타 경기 수(지금 경기에 안타가 없으면 0). 타수 없는 경기(볼넷뿐 등)는 건너뛴다 */
    static int hitStreak(List<Game> season) {
        int streak = 0;
        for (Game game : season.reversed()) {
            if (!game.batted()) {
                continue;
            }
            if (game.hits() > 0) {
                streak++;
            } else if (game.atBats() > 0) {
                break;
            }
        }
        return streak;
    }

    private static boolean isSeasonBest(Game now, List<Game> before) {
        if (!now.batted() || now.score() < BEST_MIN_SCORE) {
            return false;
        }
        List<Game> batted = before.stream().filter(Game::batted).toList();
        if (batted.size() < BEST_AFTER_GAMES) {
            return false;
        }
        double best = batted.stream().mapToDouble(Game::score).max().orElse(0);
        return now.score() > best;
    }
}
