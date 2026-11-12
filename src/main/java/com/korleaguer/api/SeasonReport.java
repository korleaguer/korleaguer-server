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
 * @param stories     자랑거리(연속 안타·최고 경기, 두 개까지). 출전이 적었던·아쉬운 시즌은 늘 비어 있다
 * @param winterMoves 정규시즌이 끝나고 3일 뒤부터의 이동 기록(FA 선언 등), 최신순 세 개까지
 * @param news        정규시즌이 끝난 뒤 나온 이 선수 관련 기사(늦은 순, 같은 기사는 한 줄, 최대 12건, 이슈 #119로 모은 것).
 *                    화면은 '이번 겨울' 칸에 앞 두 건과 'n건 더 보기'를 보인다
 */
public record SeasonReport(long playerId, SeasonTier tier, String label, String headline, String reason, Level level,
                           Double war, List<String> stories, List<WinterMove> winterMoves,
                           List<PostingSummary.Headline> news) {

    /** 기사를 붙인 성적표(기사는 DB를 읽는 SeasonReportFinder가 붙인다) */
    SeasonReport withNews(List<PostingSummary.Headline> headlines) {
        return new SeasonReport(playerId, tier, label, headline, reason, level, war, stories, winterMoves,
                List.copyOf(headlines));
    }

    /** 겨울 이동 한 줄. label은 이동 기록 이름(support.MoveLabels) */
    public record WinterMove(LocalDate date, String label) {
    }
}
