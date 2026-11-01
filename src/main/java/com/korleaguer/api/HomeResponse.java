package com.korleaguer.api;

import java.time.Instant;
import java.util.List;

/** GET /api/home */
public record HomeResponse(
        String dataMode,
        boolean statsAvailable,
        int season,
        Instant generatedAt,
        RecentGames recent,
        List<HitterSeason> hitters,
        List<PitcherSeason> pitchers) {
}
