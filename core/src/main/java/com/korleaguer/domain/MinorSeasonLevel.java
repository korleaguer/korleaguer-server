package com.korleaguer.domain;

/**
 * 마이너 경기 기록에 나오는 (시즌, 공식 날짜의 연도, 단계) 한 가지(MinorGameLogRepository.findSeasonLevels 결과).
 * 리그 평균을 받을 조합을 정할 때 쓴다. 단계를 모르는 경기는 level이 null이다
 */
public record MinorSeasonLevel(Integer season, Integer gameYear, String level) {
}
