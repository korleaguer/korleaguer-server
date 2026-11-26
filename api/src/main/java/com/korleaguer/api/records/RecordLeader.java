package com.korleaguer.api.records;

import org.jspecify.annotations.Nullable;

/** 통산 순위 한 줄. rank는 같은 값이면 같은 등수(1, 2, 2, 4), firstSeason·lastSeason은 MLB에서 뛴 첫·마지막 시즌 */
public record RecordLeader(
        int rank,
        @Nullable Long playerId,
        long mlbId,
        String nameKo,
        @Nullable String photoUrl,
        int value,
        int firstSeason,
        int lastSeason,
        boolean active) {
}
