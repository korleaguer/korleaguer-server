package com.korleaguer.api.records;

import java.time.Instant;
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
        List<RecordCategory> categories,
        List<RecordNext> next,
        List<RecordPassed> passed,
        List<RecordBest> singleSeason) {

}
