package com.korleaguer.api.player;

/**
 * 과거 소속팀(KBO) 시절 공식 사진 한 장(명단 baseball.yml의 career-photos). 우리가 호스팅하는 주소다(/photos/career-67341-2023).
 * 지금 MLB 사진(배경 없는 사진)과 같은 틀(투명 바탕, 같은 얼굴 크기·위치)의 편집본이라 화면은 두 사진을 같은 방식으로 보인다.
 *
 * @param teamKo 팀 이름(예: "키움 히어로즈")
 * @param years  그 팀에서 뛴 해(예: "2017–2023")
 */
public record PastTeamPhoto(String teamKo, String years, String photoUrl) {
}
