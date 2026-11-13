package com.korleaguer.api;

import java.time.OffsetDateTime;

/**
 * 선수 화면 '경기 기록'의 마이너 경기 한 줄(이슈 #145). 웹이 메이저 경기 줄(GameLine)과 날짜순으로 섞어 보인다.
 * <ul>
 *   <li>startTimeKst: 시작 시각(한국 시각). MLB 일정을 아직 받지 못했으면 공식 날짜(미국) 다음 날 08:00으로 어림한다</li>
 *   <li>level·levelKo: 단계 약어(AAA·AA·A+·A·ROK)와 화면 이름(트리플A·더블A·하이싱글A·싱글A·루키)</li>
 *   <li>teamId·opponentId: MLB 팀 번호(로고용, 모르면 null). teamKo·opponentKo: 한국어 도시 이름(roster/minor-teams.yml),
 *       표에 없는 팀은 영어 지명(예: "Quad Cities"), 그것도 모르면 영어 팀 이름</li>
 *   <li>home: 선수 팀이 홈이었는지(모르면 null)</li>
 *   <li>teamScore·opponentScore: 점수. 경기 전이거나 아직 모르면 null</li>
 *   <li>result: 끝난 경기의 W·L·T. 점수를 모르면 경기별 기록의 승패(W·L), 끝나지 않았거나 모르면 null</li>
 *   <li>summary: 메이저 줄과 같은 규칙의 한 줄 기록(GameSummary, 선수 주 역할 기준). batting·pitching도 GameLine과 같은 모양이다</li>
 * </ul>
 */
public record MinorGameLine(
        long gamePk,
        OffsetDateTime startTimeKst,
        String level,
        String levelKo,
        Long teamId,
        String teamKo,
        Long opponentId,
        String opponentKo,
        Boolean home,
        Integer teamScore,
        Integer opponentScore,
        String result,
        String summary,
        GameBatting batting,
        GamePitching pitching) {
}
