package com.korleaguer.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 타율·출루율·장타율·평균자책점과 이닝 계산. 분모가 0이면 null을 돌려준다. */
public final class BaseballMath {

    private static final Logger log = LoggerFactory.getLogger(BaseballMath.class);

    private BaseballMath() {
    }

    /** 타율 = H / AB */
    public static Double avg(int hits, int atBats) {
        return round(divide(hits, atBats), 3);
    }

    /** 출루율 = (H + BB + HBP) / (AB + BB + HBP + SF) */
    public static Double obp(int hits, int walks, int hitByPitch, int atBats, int sacFlies) {
        return round(rawObp(hits, walks, hitByPitch, atBats, sacFlies), 3);
    }

    /** 장타율 = TB / AB */
    public static Double slg(int totalBases, int atBats) {
        return round(divide(totalBases, atBats), 3);
    }

    /** OPS = 출루율 + 장타율(반올림 전 값끼리 더한다) */
    public static Double ops(int hits, int walks, int hitByPitch, int atBats, int sacFlies, int totalBases) {
        Double obp = rawObp(hits, walks, hitByPitch, atBats, sacFlies);
        Double slg = divide(totalBases, atBats);
        if (obp == null || slg == null) {
            return null;
        }
        return round(obp + slg, 3);
    }

    /** 평균자책점 = ER × 27 / 아웃 수 */
    public static Double era(int earnedRuns, int outs) {
        return round(divide(earnedRuns * 27, outs), 2);
    }

    /**
     * 이닝 표기(5.2)를 아웃 수(17)로. 소수점 자리는 1/3이닝 개수(.0/.1/.2)다.
     * 소수점이 그렇지 않으면(5.333, 5.5 등) 10진수 이닝으로 보고 반올림한다.
     */
    public static Integer outsFromIp(Double ip) {
        if (ip == null) {
            return null;
        }
        int whole = (int) Math.floor(ip);
        double tenths = (ip - whole) * 10;
        long thirds = Math.round(tenths);
        if (Math.abs(tenths - thirds) < 1e-6 && thirds <= 2) {
            return whole * 3 + (int) thirds;
        }
        int outs = (int) Math.round(ip * 3);
        log.warn("unexpected innings value {}, reading it as decimal innings: {} outs", ip, outs);
        return outs;
    }

    /** 아웃 수를 "5⅔"처럼 쓴다. 0이닝 2아웃은 "⅔"이다. */
    public static String inningsText(int outs) {
        int whole = outs / 3;
        String fraction = switch (outs % 3) {
            case 1 -> "⅓";
            case 2 -> "⅔";
            default -> "";
        };
        if (fraction.isEmpty()) {
            return String.valueOf(whole);
        }
        return whole == 0 ? fraction : whole + fraction;
    }

    private static Double rawObp(int hits, int walks, int hitByPitch, int atBats, int sacFlies) {
        return divide(hits + walks + hitByPitch, atBats + walks + hitByPitch + sacFlies);
    }

    private static Double divide(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private static Double round(Double value, int scale) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
