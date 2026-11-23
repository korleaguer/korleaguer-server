package com.korleaguer.api;

import com.korleaguer.domain.Level;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/players/timeline(선수별 시즌 이동 연표). 웹 선수 탭이 선수마다 시즌 띠(날짜별로 메이저·마이너·부상·팀 없음)와
 * 이동 목록을 그린다. 선수 화면(GET /api/players/{id}) 여러 개를 받지 않고 한 번에 준다. 만드는 규칙은 PlayerTimelines.
 *
 * @param season   시즌(요청한 값, 없으면 홈과 같은 지금 시즌)
 * @param seasons  경기 기록이 있는 시즌(오름차순). 웹의 연도 고르기용
 * @param from     띠 시작(그 시즌 3월 1일)
 * @param to       띠 끝: 오늘(한국 날짜)과 다음 해 2월 말일 중 이른 날(지난 시즌이면 다음 해 2월 말일)
 * @param today    오늘(한국 날짜)
 * @param inSeason 지금 정규시즌 기간 중인지(홈의 비시즌 판정과 같은 기준). 지난 시즌이면 false
 * @param players  등록 선수 전원, /api/players 순서(id 순) 그대로. 정렬은 웹이 한다
 */
public record PlayerTimelineResponse(int season, List<Integer> seasons, LocalDate from, LocalDate to, LocalDate today,
                                     boolean inSeason, List<PlayerTimeline> players) {

    /**
     * 선수 한 명.
     *
     * @param segments   띠 구간. from~to를 빈틈없이 덮는다(같은 종류가 이어지면 합친다). 그 시즌 기록이 없으면 빈 목록
     * @param moves      이 기간의 이동 전부(날짜 오름차순). 선수 화면의 moves와 같은 꼴·이름이다
     * @param lastMoveOn 이 기간 마지막 이동 날짜. 이동이 없으면 null
     * @param stat       펼친 줄에 쓸 시즌 숫자 한 줄. 표본이 없으면 null
     */
    public record PlayerTimeline(long playerId, List<Segment> segments, List<PlayerPageResponse.Move> moves,
                                 LocalDate lastMoveOn, Stat stat) {
    }

    /** 띠 구간 종류. MLB 메이저, MINORS 마이너, INJURED 부상자 명단(재활 경기 배정 포함), NO_TEAM 방출·FA 선언 뒤 계약 전 */
    public enum Kind { MLB, MINORS, INJURED, NO_TEAM }

    /**
     * 띠 구간(양 끝 날짜 포함). 부상 구간의 끝은 해제일 전날이다.
     *
     * @param levelKo 마이너 단계 이름(트리플A 등). 부상 구간은 있던 단계. 모르거나 메이저·팀 없음이면 null
     * @param teamKo  그 구간의 팀(이동 기록의 팀 이름, 마이너는 "오클라호마시티"처럼 지명만). 모르거나 팀 없음이면 null
     */
    public record Segment(LocalDate from, LocalDate to, Kind kind, String levelKo, String teamKo) {
    }

    /**
     * 시즌 숫자 한 줄. 홈 비시즌 성적표(offseason.report)와 같은 기준이라 같은 선수의 같은 시즌이면 같은 값이다
     * (SeasonRates: 메이저는 MLB 공식 wRC+·ERA-로 낸 값, 마이너는 단계 리그 평균과 견준 값). 시즌 중에도 나온다.
     *
     * @param level   주로 뛴 단계. 성적표가 마이너 합계를 앞세우면 MINORS, 아니면 MLB
     * @param levelKo level이 MINORS일 때 가장 많이 뛴 마이너 단계 이름(트리플A 등). MLB면 null
     * @param games   그 단계에서 기록을 낸 경기 수(타자 타격 기록이 있는 경기, 투수 등판)
     * @param wrcPlus 타자 시즌 wRC+(리그 평균 = 100). 투수거나 표본(50타석)이 작으면 null
     * @param avg     타자 타율. 투수거나 타수가 없으면 null
     * @param eraPlus 투수 시즌 ERA+(리그 평균 = 100, 높을수록 잘함). 타자거나 표본(10이닝)이 작으면 null
     * @param era     투수 평균자책점. 타자거나 아웃이 없으면 null
     */
    public record Stat(Level level, String levelKo, int games, Integer wrcPlus, Double avg, Integer eraPlus,
                       Double era) {
    }
}
