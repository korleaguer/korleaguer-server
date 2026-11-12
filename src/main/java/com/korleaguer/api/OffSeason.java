package com.korleaguer.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 홈의 비시즌 정보(GET /api/home의 offseason). 비시즌일 때만 있고 시즌 중이면 offseason 자체가 null이다(OffSeasonFinder).
 *
 * @param season         끝난 시즌(지금 시즌). 포스트시즌에서 우리 선수 구단이 모두 탈락했으면 포스트시즌이 아직 이어지는 시즌이다
 * @param nextSeason     다음 정규시즌 연도
 * @param nextOpeningDay 다음 정규시즌 개막일(미국 날짜). MLB가 아직 발표하지 않았거나 받지 못했으면 null
 * @param daysToOpening  오늘(미국 날짜)부터 개막일까지 남은 날 수. 개막일이 null이면 null
 * @param playerIds      명단의 선수 번호. 화면이 이 번호로 /api/my를 불러 선수별 상태·시즌 합계를 받는다
 * @param report         선수별 시즌 성적표(SeasonReport). 좋은 시즌 먼저, 그 안에서 메이저 경기 많은 순.
 *                       만들지 못했으면 빈 목록
 */
public record OffSeason(int season, int nextSeason, LocalDate nextOpeningDay, Integer daysToOpening,
                        List<Long> playerIds, List<SeasonReport> report) {

    /** 성적표만 바꾼 사본 */
    OffSeason withReport(List<SeasonReport> report) {
        return new OffSeason(season, nextSeason, nextOpeningDay, daysToOpening, playerIds, report);
    }
}
