package com.korleaguer.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/records: 한국인 메이저리거 역대 기록(기록 탭, 이슈 #177). 모두 MLB 정규시즌 기록이다.
 * <ul>
 *   <li>categories: 통산 순위. 타자(HITTING) 안타·홈런·경기·도루·타점, 투수(PITCHING) 승·탈삼진·세이브. 값이 0인 선수는 빠진다.</li>
 *   <li>next: 현역 선수마다·기록마다 바로 위 선수와 넘으려면 남은 수. 넘기 쉬운(남은 수 ÷ 상대 값이 작은) 순이다.</li>
 *   <li>passed: 이번 시즌(season) 현역 선수가 현역이 아닌 선배의 통산 기록을 넘은 경기. 최근 것부터.</li>
 *   <li>singleSeason: 한 시즌 최다(안타·홈런·도루·승·탈삼진·세이브) 10위까지(같은 값이면 함께).</li>
 * </ul>
 * 현역(active)은 지금 명단 선수 중 국내 리그로 돌아가지 않은 선수다. playerId·photoUrl은 명단 선수만 있다(선수 화면·사진).
 * unit은 숫자 뒤에 붙이는 말이다(예: 336 + "안타").
 */
public record RecordsResponse(
        String dataMode,
        int season,
        Instant generatedAt,
        List<Category> categories,
        List<Next> next,
        List<Passed> passed,
        List<Best> singleSeason) {

    /** 한 기록의 통산 순위. group: HITTING / PITCHING */
    public record Category(String key, String label, String group, String unit, List<Leader> leaders) {
    }

    /** 통산 순위 한 줄. rank는 같은 값이면 같은 등수(1, 2, 2, 4), firstSeason·lastSeason은 MLB에서 뛴 첫·마지막 시즌 */
    public record Leader(int rank, Long playerId, long mlbId, String nameKo, String photoUrl, int value,
                         int firstSeason, int lastSeason, boolean active) {
    }

    /**
     * 다음 선배. value·rank는 지금 통산 값과 등수, target*는 바로 위 선수(같은 값이 여럿이면 순위표에서 앞선 선수),
     * toPass는 넘으려면 남은 수(같아지려면 toPass - 1), rankAfter는 넘으면 오르는 등수다.
     */
    public record Next(Long playerId, long mlbId, String nameKo, String photoUrl, String category, String label,
                       String unit, int value, int rank, String targetNameKo, int targetValue, boolean targetActive,
                       int toPass, int rankAfter) {
    }

    /** 이번 시즌 넘은 선배. dateKst는 넘은 경기의 한국 날짜, passedValue는 넘은 선배의 통산 값 */
    public record Passed(Long playerId, long mlbId, String nameKo, String photoUrl, String category, String label,
                         String unit, LocalDate dateKst, String passedNameKo, int passedValue) {
    }

    /** 한 시즌 최다 */
    public record Best(String key, String label, String group, String unit, List<SeasonRow> rows) {
    }

    public record SeasonRow(int rank, Long playerId, long mlbId, String nameKo, int season, int value,
                            boolean active) {
    }
}
