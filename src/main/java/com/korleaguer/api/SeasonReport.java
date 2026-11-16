package com.korleaguer.api;

import com.korleaguer.domain.Level;

import java.time.LocalDate;
import java.util.List;

/**
 * 비시즌 홈의 선수별 시즌 성적표 한 장(offseason.report의 한 줄, 이슈 #118). 만드는 규칙은 SeasonReports.
 *
 * @param playerId    선수 번호. 화면이 /api/my의 사진·상태·시즌 합계와 이 번호로 합친다
 * @param tier        판정(SeasonTier). 홈은 이 순서(좋은 시즌 먼저), 그 안에서 메이저 경기 많은 순이다
 * @param label       판정 이름(좋은 시즌 등). 마이너 시즌은 '트리플A에서 보낸 시즌' 또는 '마이너에서 보낸 시즌'
 * @param headline    한 줄 요약(예: "143경기 완주, 타격은 리그 평균")
 * @param reason      왜 그런 시즌이었나(부상·강등 등 사실 두 개까지 " · "로 잇는다). 없으면 null
 * @param level       카드가 앞세울 시즌 합계. 마이너 시즌이면 MINORS, 아니면 MLB
 * @param war         메이저 WAR(소수 첫째 자리). 모르면 null. 화면은 좋은 시즌·제 몫 한 시즌에만 보인다
 * @param stories     자랑거리(연속 안타·최고 경기, 두 개까지). 출전이 적었던·아쉬운 시즌은 늘 비어 있다.
 *                    웹은 달별 막대(months)로 바꾼 뒤 쓰지 않는다. 운영 웹이 바뀐 다음 뺀다(이슈 #170)
 * @param months      정규시즌 달마다 어디서 뛰었나와 타율·평균자책점(SeasonMonths, 이슈 #170). 홈 카드의 달별 막대
 * @param winterMoves 정규시즌이 끝나고 3일 뒤부터의 이동 기록(FA 선언 등), 최신순 세 개까지
 * @param news        정규시즌이 끝난 뒤 나온 이 선수 관련 기사(늦은 순, 같은 기사는 한 줄, 최대 12건, 이슈 #119로 모은 것).
 *                    화면은 '이번 겨울' 칸에 앞 두 건과 'n건 더 보기'를 보인다. 같은 언론사가 연달아 오지 않게 섞었다
 * @param linkedTeams FA 선수의 행선지로 거론된 구단(최근 7일, 포스팅 카드와 같은 규칙, 직전 구단은 세지 않는다, 이슈 #140).
 *                    FA가 아니거나 거론된 구단이 없으면 null
 */
public record SeasonReport(long playerId, SeasonTier tier, String label, String headline, String reason, Level level,
                           Double war, List<String> stories, List<Month> months, List<WinterMove> winterMoves,
                           List<PostingSummary.Headline> news, PostingSummary.LinkedTeams linkedTeams) {

    /** 기사와 거론 구단을 붙인 성적표(DB를 읽는 SeasonReportFinder가 붙인다) */
    SeasonReport withNews(List<PostingSummary.Headline> headlines, PostingSummary.LinkedTeams linked) {
        return new SeasonReport(playerId, tier, label, headline, reason, level, war, stories, months, winterMoves,
                List.copyOf(headlines), linked);
    }

    /**
     * 달 한 칸(SeasonMonths).
     *
     * @param month   달(4~9). 개막 직후 며칠(3월 말)은 4월 칸에 들어 있다
     * @param kind    어디서 뛰었나. MLB·MINORS만 기록이 있다
     * @param levelKo 마이너 단계 이름(트리플A 등). MINORS가 아니면 null
     * @param games   기록을 낸 경기 수(타자 경기, 투수 등판). 재활 경기는 세지 않는다
     * @param avg     타자 타율. 투수거나 타수가 없으면 null
     * @param era     투수 평균자책점. 타자거나 아웃이 없으면 null
     * @param few     경기가 적은 달(타자 10경기·투수 5등판 미만). 화면이 막대를 옅게 그린다
     * @param hot     아주 잘한 달(메이저 타자 15경기 이상 .330 이상, 투수 15이닝 이상 2.00 이하). 화면이 숫자를 주황으로
     */
    public record Month(int month, Kind kind, String levelKo, int games, Double avg, Double era, boolean few,
                        boolean hot) {

        /** MLB 메이저 · MINORS 마이너 · INJURED 부상(재활 경기만 뛰었거나 경기 없이 부상자 명단) · FREE_AGENT 팀 없음 · NONE 기록 없음 */
        public enum Kind { MLB, MINORS, INJURED, FREE_AGENT, NONE }
    }

    /** 겨울 이동 한 줄. label은 이동 기록 이름(support.MoveLabels) */
    public record WinterMove(LocalDate date, String label) {
    }
}
