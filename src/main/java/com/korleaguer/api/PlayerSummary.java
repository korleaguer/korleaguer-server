package com.korleaguer.api;

import com.korleaguer.domain.Role;

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
}
