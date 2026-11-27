package com.korleaguer.api.screen;

import com.korleaguer.api.player.PlayerSummary;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * GET /api/players/{id}/compare(이슈 #221). 두 선수 비교 창에 쓸 타자 시즌 성적 묶음이다.
 * me는 이 선수의 그 시즌 메이저 정규시즌 성적, others는 견줄 수 있는 다른 우리 타자들(메이저 타석이 하나라도 있는 타자, 타석 많은 순)이다.
 * 이 선수가 투수거나 그 시즌 메이저 타석이 없으면 me가 null이고 others는 빈 목록이다(비교 입구가 없다).
 * 투수끼리 비교는 주지 않는다(2026 메이저 투수가 한 명이라 짝이 없다).
 */
public record CompareResponse(int season, long playerId, @Nullable Line me, List<Line> others) {

    /**
     * 타자 한 명의 줄. 숫자는 홈 시즌 기록(HitterSeason)과 같은 계산이고, wrcPlus는 선수 페이지 saber와 같은
     * MLB 공식 시즌 wRC+(리그 평균 = 100, 소수 첫째 자리)다. 그 시즌 리그 기록에 없으면 null이다.
     * 표본이 적어도(4타석) 값은 그대로 준다: 참고용 표시는 plateAppearances를 보고 웹이 한다.
     */
    public record Line(
            PlayerSummary player,
            int games,
            int plateAppearances,
            @Nullable Double avg,
            int homeRuns,
            int rbi,
            int stolenBases,
            @Nullable Double ops,
            @Nullable Double wrcPlus) {
    }
}
