package com.korleaguer.api;

import com.korleaguer.domain.Role;

import java.util.Comparator;
import java.util.List;

/**
 * 시즌 성적표 판정 규칙(이슈 #118). 지난 89개 시즌(해외파 선수들)으로 맞춰 본 규칙을 그대로 옮겼다.
 * 숫자는 모두 메이저 정규시즌 기록이다. 2020년(60경기 시즌)은 기준을 60/162로 줄인다(k).
 * <p>
 * 타자(양은 타석 PA): 다 뛴 시즌 = 300k타석, 어느 정도 뛴 시즌 = 150k타석.
 * <ul>
 *   <li>좋은 타격 = wRC+ 110 이상. 좋은 시즌(GOOD) = 다 뛰었고 좋은 타격이거나 WAR 2.5k 이상</li>
 *   <li>짧았지만 좋았던 시즌(SHORT_GOOD) = 어느 정도 뛰었지만 다 뛰지 못했고 좋은 타격</li>
 *   <li>아쉬운 시즌(POOR) = 어느 정도 뛰었고 (wRC+ 80 미만 또는 WAR -0.3 미만),
 *       또는 앞 시즌들에 주전이었는데(앞 3시즌 중 200타석 이상 시즌들의 600타석당 WAR 평균 2.5 이상) 다 뛰지 못했다</li>
 *   <li>제 몫 한 시즌(SOLID) = 다 뛰었다. 마이너 시즌(MINORS) = 트리플A 타석이 메이저 타석보다 많다. 나머지는 출전이 적었던 시즌(SHORT)</li>
 * </ul>
 * 투수(양은 이닝 IP): 다 뛴 시즌 = 선발 10경기 이상(2020년은 5경기 이상)이면 100k이닝, 아니면 40k이닝. 어느 정도 = 30k이닝.
 * 좋은 투구 = ERA- 90 이하, 나쁜 투구 = ERA- 120 이상, 앞 시즌 주전 = 40이닝 이상 시즌들의 180이닝당 WAR 평균 2.5 이상.
 * 마이너 시즌은 마이너 전체(트리플A·더블A 등) 이닝이 메이저 이닝보다 많을 때다.
 * <p>
 * 확인 순서: 좋은 시즌 → 짧았지만 좋았던 시즌 → 아쉬운 시즌 → 제 몫 한 시즌 → 마이너 시즌 → 출전이 적었던 시즌.
 * 지표(wRC+·ERA-·WAR)가 없으면(받기 전, 실패) 그 조건은 맞지 않는 것으로 본다(양만으로 판정).
 */
final class SeasonVerdict {

    /** 앞 시즌 주전 판단에 쓰는 최근 시즌 수 */
    static final int REGULAR_BEFORE_SEASONS = 3;

    private SeasonVerdict() {
    }

    /**
     * 한 시즌 메이저 기록.
     *
     * @param volume       타자는 타석, 투수는 이닝(50⅔이닝 = 50.667)
     * @param gamesStarted 투수의 선발 등판 수(타자는 0)
     * @param quality      타자는 wRC+, 투수는 ERA-. 모르면 null
     * @param war          모르면 null
     */
    record Line(int season, double volume, int gamesStarted, Double quality, Double war) {
    }

    /**
     * @param minorVolume 같은 시즌 마이너 양. 타자는 트리플A 타석, 투수는 마이너 전체 이닝
     * @param previous    앞 시즌들(순서 무관). 앞 3시즌 중 기준을 넘는 시즌만 본다
     */
    static SeasonTier judge(Role role, Line current, double minorVolume, List<Line> previous) {
        boolean pitcher = role == Role.PITCHER;
        double k = scale(current.season());
        double volume = current.volume();
        double full = pitcher ? (current.gamesStarted() >= 10 || (current.gamesStarted() >= 5 && k < 1) ? 100 : 40) * k
                : 300 * k;
        double some = (pitcher ? 30 : 150) * k;
        Double quality = current.quality();
        Double war = current.war();

        boolean goodQuality = quality != null && (pitcher ? quality <= 90 : quality >= 110);
        if (volume >= full && (goodQuality || (war != null && war >= 2.5 * k))) {
            return SeasonTier.GOOD;
        }
        if (volume >= some && volume < full && goodQuality) {
            return SeasonTier.SHORT_GOOD;
        }
        boolean poorQuality = volume >= some
                && ((quality != null && (pitcher ? quality >= 120 : quality < 80)) || (war != null && war < -0.3));
        if (poorQuality || (regularBefore(pitcher, current.season(), previous) && volume < full)) {
            return SeasonTier.POOR;
        }
        if (volume >= full) {
            return SeasonTier.SOLID;
        }
        if (minorVolume > volume) {
            return SeasonTier.MINORS;
        }
        return SeasonTier.SHORT;
    }

    /** 2020년(60경기 시즌)은 기준을 60/162로 줄인다. */
    static double scale(int season) {
        return season == 2020 ? 60.0 / 162 : 1;
    }

    /** 앞 3시즌 중 기준(200타석·40이닝) 이상 시즌들의 600타석(180이닝)당 WAR 평균이 2.5 이상인지 */
    static boolean regularBefore(boolean pitcher, int season, List<Line> previous) {
        double minimum = pitcher ? 40 : 200;
        double per = pitcher ? 180 : 600;
        List<Double> rates = previous.stream()
                .filter(l -> l.season() < season && l.season() >= season - REGULAR_BEFORE_SEASONS)
                .filter(l -> l.volume() >= minimum && l.war() != null)
                .sorted(Comparator.comparingInt(Line::season).reversed())
                .limit(REGULAR_BEFORE_SEASONS)
                .map(l -> l.war() / l.volume() * per)
                .toList();
        if (rates.isEmpty()) {
            return false;
        }
        return rates.stream().mapToDouble(Double::doubleValue).average().orElse(0) >= 2.5;
    }
}
