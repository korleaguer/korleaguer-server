package com.korleaguer.api.player;

import com.korleaguer.domain.Level;
import org.jspecify.annotations.Nullable;
import java.time.LocalDate;

/**
 * 이동 기록 한 줄. labelKo는 서버가 typeCode(와 설명 글)로 만든 짧은 한국어 이름이다(예: "메이저 콜업").
 * fromTeam·toTeam은 MLB 구단이면 한글 이름(예: "미네소타"), 마이너 팀 이름표(roster/minor-teams.yml: 미국의 마이너 팀
 * 모두)에 있으면 경기 줄과 같은 한국어 지명에 단계를 붙인 이름(예: "귀넷(트리플A)", "콜럼버스(더블A)", 이슈 #183,
 * "주피터(싱글A)", "FCL 브레이브스(루키)", 이슈 #191), 그 밖의 팀(도미니칸 서머 리그·국가대표 등)은 MLB가 준 영어 이름
 * 그대로이고 없으면 null. toLevel은 옮겨 간 팀의 단계(MLB / MINORS), 모르면 null이다.
 */
public record MoveEntry(
        LocalDate date,
        String typeCode,
        String labelKo,
        @Nullable String fromTeam,
        @Nullable String toTeam,
        @Nullable Level toLevel) {
}
