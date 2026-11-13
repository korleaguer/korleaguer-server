package com.korleaguer.api;

/**
 * 한 경기 타격 숫자. 앞 여섯 칸(타수·안타·홈런·타점·볼넷·도루)은 빈 칸(null)을 0으로 둔다.
 * 뒤 여섯 칸(2루타·3루타·몸에 맞는 공·희생플라이·타석·삼진)은 선수 화면의 타격 흐름 그래프(경기별 누적 wRC+, 웹이 계산)에
 * 쓰며 MLB가 주지 않았으면 null이다(이슈 #133).
 */
public record GameBatting(
        int atBats,
        int hits,
        int homeRuns,
        int rbi,
        int walks,
        int stolenBases,
        Integer doubles,
        Integer triples,
        Integer hitByPitch,
        Integer sacFlies,
        Integer plateAppearances,
        Integer strikeouts) {
}
