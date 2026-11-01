package com.korleaguer.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** balldontlie 키(피트·인치)와 몸무게(파운드)를 cm·kg 정수로 바꾼다. 읽을 수 없으면 null. */
public final class BodyMeasures {

    private static final BigDecimal CM_PER_INCH = new BigDecimal("2.54");
    private static final BigDecimal KG_PER_POUND = new BigDecimal("0.45359237");

    // 6' 2" / 6'2" / 6-2 / 6 ft 2 in
    private static final Pattern HEIGHT = Pattern.compile(
            "(\\d)\\s*(?:'|-|ft\\.?|feet)\\s*(\\d{1,2})\\s*(?:\"|in\\.?|inch(?:es)?)?", Pattern.CASE_INSENSITIVE);
    // 194 lbs / 194 lb / 194
    private static final Pattern WEIGHT = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(?:lbs?\\.?|pounds?)?", Pattern.CASE_INSENSITIVE);

    private BodyMeasures() {
    }

    /** 6' 2" → 188 (cm, 반올림). 인치가 11을 넘으면(6-12 등) 읽지 않는다. */
    public static Integer heightCm(String height) {
        if (height == null) {
            return null;
        }
        Matcher matcher = HEIGHT.matcher(height.trim());
        if (!matcher.matches()) {
            return null;
        }
        int feet = Integer.parseInt(matcher.group(1));
        int inches = Integer.parseInt(matcher.group(2));
        if (inches > 11) {
            return null;
        }
        return round(BigDecimal.valueOf(feet * 12L + inches).multiply(CM_PER_INCH));
    }

    /** 194 lbs → 88 (kg, 반올림). 단위가 없으면 파운드로 본다. */
    public static Integer weightKg(String weight) {
        if (weight == null) {
            return null;
        }
        Matcher matcher = WEIGHT.matcher(weight.trim());
        if (!matcher.matches()) {
            return null;
        }
        BigDecimal pounds = new BigDecimal(matcher.group(1));
        if (pounds.signum() <= 0) {
            return null;
        }
        return round(pounds.multiply(KG_PER_POUND));
    }

    private static int round(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).intValue();
    }
}
