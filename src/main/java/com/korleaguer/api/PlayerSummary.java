package com.korleaguer.api;

import com.korleaguer.domain.Player;
import com.korleaguer.domain.Role;
import com.korleaguer.domain.Team;
import com.korleaguer.support.BirthDates;
import com.korleaguer.support.PositionNames;

import java.time.LocalDate;

/**
 * GET /api/players 목록의 한 줄. 매칭 전인 선수는 영어 이름·포지션·팀·나이·등번호가 null이다.
 * photoUrl은 공식 사진 주소로, 명단에 MLB 선수 번호가 없거나 사진을 끄면 null이다.
 */
public record PlayerSummary(
        long id,
        String nameKo,
        String photoUrl,
        String nameEn,
        Role role,
        String position,
        String positionKo,
        String teamKo,
        String teamAbbr,
        Integer age,
        String jersey) {

    /** team은 현재 소속(없으면 null), todayKst는 나이를 셀 한국 날짜다. */
    static PlayerSummary of(Player p, String photoUrl, Team team, LocalDate todayKst) {
        return new PlayerSummary(p.getId(), p.getNameKo(), photoUrl, p.getNameEn(), p.role(),
                p.getPosition(), PositionNames.korean(p.getPosition()), TeamLabels.korean(team),
                TeamLabels.abbreviation(team), BirthDates.age(p.getBirthDate(), p.getAge(), todayKst),
                p.getJersey());
    }
}
