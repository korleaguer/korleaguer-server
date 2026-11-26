package com.korleaguer.api.player;

import com.korleaguer.domain.Level;
import com.korleaguer.domain.PlayerMove;
import com.korleaguer.support.MoveLabels;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        String fromTeam,
        String toTeam,
        Level toLevel) {

    /**
     * 이동 기록(최신순) 중 팬에게 보일 것만 고른다. 이름(labelKo)이 없는 줄(의미 없는 상태 변경)은 빼고,
     * MLB가 같은 날 같은 내용을 두 번 준 줄은 하나만 남긴다. 팀 이름은 TeamLabels.moveTeam(MLB 구단은 한글 이름,
     * 트리플A·더블A 팀은 "귀넷(트리플A)")이다.
     */
    public static List<MoveEntry> fanMoves(List<PlayerMove> newestFirst, MinorTeamNames minorTeams) {
        record Key(LocalDate date, String typeCode, String description) {
        }
        Set<Key> seen = new HashSet<>();
        List<MoveEntry> result = new ArrayList<>();
        for (PlayerMove move : newestFirst) {
            String label = MoveLabels.korean(move.getTypeCode(), move.getTypeDesc(), move.getDescription());
            if (label == null || !seen.add(new Key(move.getMoveDate(), move.getTypeCode(), move.getDescription()))) {
                continue;
            }
            result.add(new MoveEntry(move.getMoveDate(), move.getTypeCode(), label,
                    TeamLabels.moveTeam(move.getFromTeamId(), move.getFromTeamName(), minorTeams),
                    TeamLabels.moveTeam(move.getToTeamId(), move.getToTeamName(), minorTeams),
                    move.getToLevel()));
        }
        return result;
    }
}
