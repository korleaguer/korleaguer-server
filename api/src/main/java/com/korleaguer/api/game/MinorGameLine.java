package com.korleaguer.api.game;

import com.korleaguer.api.player.MoveEntry;

import com.korleaguer.domain.SeasonType;

import java.time.OffsetDateTime;

/**
 * 선수 화면 '경기 기록'의 마이너 경기 한 줄(이슈 #145). 웹이 메이저 경기 줄(GameLine)과 날짜순으로 섞어 보인다.
 * <ul>
 *   <li>startTimeKst: 시작 시각(한국 시각). MLB 일정을 아직 받지 못했으면 공식 날짜(미국) 다음 날 08:00으로 어림한다.
 *       내 선수 카드의 마이너 경기 줄·기간·마지막 경기 날짜도 같은 값이다(MinorRecords.startTimeKst, 이슈 #183)</li>
 *   <li>level·levelKo: 단계 약어(AAA·AA·A+·A·ROK)와 화면 이름(트리플A·더블A·하이싱글A·싱글A·루키)</li>
 *   <li>teamId·opponentId: MLB 팀 번호(로고용, 모르면 null). teamKo·opponentKo: 한국어 도시 이름(roster/minor-teams.yml),
 *       표에 없는 팀(도미니칸 서머 리그 등)은 영어 지명(예: "DSL LAD Bautista"), 그것도 모르면 영어 팀 이름. 단계는
 *       levelKo 칸이 따로 있어 붙이지 않는다(이동 기록은 팀 이름 한 칸뿐이라 붙인다, MoveEntry)</li>
 *   <li>home: 선수 팀이 홈이었는지(모르면 null)</li>
 *   <li>teamScore·opponentScore: 점수. 경기 전이거나 아직 모르면 null</li>
 *   <li>result: 끝난 경기의 W·L·T. 점수를 모르면 경기별 기록의 승패(W·L), 끝나지 않았거나 모르면 null</li>
 *   <li>summary: 메이저 줄과 같은 규칙의 한 줄 기록(GameSummary, 선수 주 역할 기준). batting·pitching도 GameLine과 같은 모양이다</li>
 *   <li>seasonType: 경기 종류(이슈 #190, GameLine.seasonType과 같은 칸). 마이너 경기 기록은 정규시즌 줄만 남겨 저장해서
 *       (MlbMinorGameLog.isRegularSeason, 마이너 포스트시즌은 저장하지 않는다) 늘 REGULAR다</li>
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
        GamePitching pitching,
        SeasonType seasonType) {
}
