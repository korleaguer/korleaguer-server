package com.korleaguer.api;

import java.time.Instant;
import java.util.List;

/**
 * GET /api/home. hitters는 순위 기준(ranking)을 채운 선수 먼저 타율 순, 그다음 못 채운 선수 타율 순이다.
 * pitchers도 같은 식으로 평균자책점 순이다. 둘 다 메이저 정규시즌 기록이다.
 * minorHitters·minorPitchers는 같은 시즌 마이너 경기별 기록을 더한 마이너 성적(levelKo에 단계), 마이너 경기 많은 순이다.
 * offseason은 비시즌일 때만 있고(다음 개막일 등, OffSeason), 시즌 중이면 null이다.
 * posting도 비시즌일 때만 있다(이번 겨울 KBO 포스팅 선수·지난 포스팅 기록·관련 기사 제목, PostingSummary).
 */
public record HomeResponse(
        String dataMode,
        boolean statsAvailable,
        int season,
        Instant generatedAt,
        RecentGames recent,
        RankingMinimums ranking,
        List<HitterSeason> hitters,
        List<PitcherSeason> pitchers,
        List<HitterSeason> minorHitters,
        List<PitcherSeason> minorPitchers,
        OffSeason offseason,
        PostingSummary posting) {
}
