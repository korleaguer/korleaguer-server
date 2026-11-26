package com.korleaguer.api.records;

import org.jspecify.annotations.Nullable;

public record RecordSeasonRow(int rank, @Nullable Long playerId, long mlbId, String nameKo, int season, int value,
                        boolean active) {
}
