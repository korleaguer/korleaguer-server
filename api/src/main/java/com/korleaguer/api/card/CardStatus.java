package com.korleaguer.api.card;

import java.time.LocalDate;

/**
 * 상태 배지. 메이저에서 평소대로 뛰고 있으면 status 자체가 null이다.
 * kind: IL(부상자 명단) · RETURNED(부상자 명단에서 복귀, 7일 안) · CALLED_UP(콜업, 7일 안) · MINORS(마이너) ·
 * MINORS_IL(마이너 부상자 명단) · SUSPENDED(출장 정지) · DFA(지명 할당) · FA · SEASON_OVER(시즌 끝) ·
 * KBO(국내 리그로 돌아감, 명단의 returned-ko. teamName에 그 팀 이름, 해외 마지막 경기는 lastGameDate·lastGameLevel).
 * since는 그 상태가 시작된 미국 날짜(이동 기록 날짜, 모르면 null), days는 since부터 오늘(미국 날짜)까지 며칠째인지
 * (since가 없으면 null). level·teamName은 마이너 단계 약어와 마이너 팀 이름(마이너 기록이 있을 때), lastGameDate·lastGameLevel은
 * 마지막 경기(메이저·마이너 중 늦게 시작한 경기)의 날짜와 단계(MLB 또는 마이너 약어, FA·시즌 끝·KBO일 때만)다.
 * lastGameDate는 그 경기 시작 시각의 한국 날짜(Asia/Seoul)로, 선수 화면 경기 달력이 같은 경기를 놓는 날과 같다(이슈 #183,
 * 예전에는 미국 날짜라 미국 밤 경기가 하루 일렀다). 마이너 일정을 아직 받지 못한 경기는 공식 날짜 다음 날이다(달력과 같다).
 */
public record CardStatus(
        String kind,
        LocalDate since,
        Integer days,
        String level,
        String teamName,
        LocalDate lastGameDate,
        String lastGameLevel) {
}
