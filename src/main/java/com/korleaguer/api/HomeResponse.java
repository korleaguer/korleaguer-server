package com.korleaguer.api;

import java.time.Instant;
import java.util.List;

/**
 * GET /api/home. hitters는 순위 기준(ranking)을 채운 선수 먼저 타율 순, 그다음 못 채운 선수 타율 순이다.
 * pitchers도 같은 식으로 평균자책점 순이다.
 */
public record HomeResponse(
        String dataMode,
        boolean statsAvailable,
        int season,
        Instant generatedAt,
        RecentGames recent,
        RankingMinimums ranking,
        List<HitterSeason> hitters,
        List<PitcherSeason> pitchers) {
}
