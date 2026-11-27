package com.korleaguer.api.player;

import com.korleaguer.domain.Level;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.Role;
import com.korleaguer.domain.Team;
import com.korleaguer.support.BirthDates;
import com.korleaguer.support.PositionNames;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;

/**
 * GET /api/players 목록의 한 줄. MLB에서 받기 전인 선수는 영어 이름·포지션·팀·나이·등번호가 null이다.
 * photoUrl은 공식 사진 주소로, 명단에 MLB 선수 번호가 없거나 사진을 끄면 null이다.
 * level·levelTeam은 MLB 공식 API 값이다. level은 지금 메이저(MLB)인지 마이너(MINORS)인지, 소속이 없거나 모르면 null.
 * levelTeam은 MLB가 알려 준 지금 팀 이름 그대로(예: "St. Paul Saints")이며, 아직 확인 전이거나 소속이 없으면 null이다.
 * freeAgent는 MLB 이동 기록상 FA인지다(마지막 'FA 선언' 뒤에 새 팀 기록이 없음). true면 level·levelTeam은 null이고,
 * teamKo·teamAbbr은 마지막 소속 팀이다.
 * returnedKo는 국내 리그로 돌아간 팀(명단에 사람이 적은 값, 예: "LG 트윈스")이고, 해외에서 뛰고 있으면 null이다.
 * photoTeamAbbr은 photoUrl 사진 속 유니폼 팀 약어다(웹 사진 원 바탕색). 트레이드·복귀 직후에는 teamAbbr과 다를 수 있고,
 * 사진이 없으면 null이다(RosterProfiles.photoTeamAbbr).
 */
public record PlayerSummary(
        long id,
        String nameKo,
        @Nullable String photoUrl,
        @Nullable String nameEn,
        Role role,
        @Nullable String position,
        @Nullable String positionKo,
        @Nullable String teamKo,
        @Nullable String teamAbbr,
        @Nullable Integer age,
        @Nullable String jersey,
        @Nullable Level level,
        @Nullable String levelTeam,
        boolean freeAgent,
        @Nullable String returnedKo,
        @Nullable String photoTeamAbbr) {

    /**
     * team은 지금 소속(소속이 끝났으면 마지막 소속, 없으면 null), todayKst는 나이를 셀 한국 날짜,
     * returnedKo는 국내 리그로 돌아간 팀(RosterProfiles)이다.
     */
    public static PlayerSummary of(Player p, String photoUrl, Team team, LocalDate todayKst, String returnedKo,
                            String photoTeamAbbr) {
        return new PlayerSummary(p.getId(), p.getNameKo(), photoUrl, p.getNameEn(), p.role(),
                p.getPosition(), PositionNames.korean(p.getPosition()), TeamLabels.korean(team),
                TeamLabels.abbreviation(team), BirthDates.age(p.getBirthDate(), p.getAge(), todayKst),
                p.getJersey(), p.getLevel(), p.getLevelTeamName(), p.isFreeAgent(), returnedKo, photoTeamAbbr);
    }
}
