package com.korleaguer.api.season;

import java.time.LocalDate;

/**
 * 경기 한 번 숫자. date는 미국 날짜, batted·pitched는 그 기록이 있는지, runsAllowed는 모르면 null이다.
 */
public record GameStat(LocalDate date, boolean batted, int atBats, int hits, int homeRuns, int plateAppearances,
                boolean pitched, Integer runsAllowed) {
}
