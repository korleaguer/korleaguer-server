package com.korleaguer.api.records;

/**
 * 다음 선배. value·rank는 지금 통산 값과 등수, target*는 바로 위 선수(같은 값이 여럿이면 순위표에서 앞선 선수),
 * toPass는 넘으려면 남은 수(같아지려면 toPass - 1), rankAfter는 넘으면 오르는 등수다.
 */
public record RecordNext(Long playerId, long mlbId, String nameKo, String photoUrl, String category, String label,
                   String unit, int value, int rank, String targetNameKo, int targetValue, boolean targetActive,
                   int toPass, int rankAfter) {
}
