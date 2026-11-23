package com.korleaguer.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BodyMeasuresTest {

    @Test
    void heightFormatsAllGiveTheSameCm() {
        // 6피트 2인치 = 74인치 = 187.96cm
        assertThat(BodyMeasures.heightCm("6' 2\"")).isEqualTo(188);
        assertThat(BodyMeasures.heightCm("6'2\"")).isEqualTo(188);
        assertThat(BodyMeasures.heightCm("6-2")).isEqualTo(188);
        assertThat(BodyMeasures.heightCm("6 ft 2 in")).isEqualTo(188);
        assertThat(BodyMeasures.heightCm("  6' 2\"  ")).isEqualTo(188);
    }

    @Test
    void heightIsRounded() {
        assertThat(BodyMeasures.heightCm("5' 10\"")).isEqualTo(178); // 177.8
        assertThat(BodyMeasures.heightCm("6' 0\"")).isEqualTo(183); // 182.88
        assertThat(BodyMeasures.heightCm("5' 11\"")).isEqualTo(180); // 180.34
        assertThat(BodyMeasures.heightCm("6' 1\"")).isEqualTo(185); // 185.42
        // 정확히 190.5cm는 올림
        assertThat(BodyMeasures.heightCm("6' 3\"")).isEqualTo(191);
    }

    @Test
    void unreadableHeightIsNull() {
        assertThat(BodyMeasures.heightCm(null)).isNull();
        assertThat(BodyMeasures.heightCm("")).isNull();
        assertThat(BodyMeasures.heightCm("tall")).isNull();
        assertThat(BodyMeasures.heightCm("188")).isNull();
        assertThat(BodyMeasures.heightCm("188 cm")).isNull();
        assertThat(BodyMeasures.heightCm("6-12")).isNull(); // 인치는 11까지
    }

    @Test
    void weightFormatsAllGiveTheSameKg() {
        // 194파운드 = 87.997kg
        assertThat(BodyMeasures.weightKg("194 lbs")).isEqualTo(88);
        assertThat(BodyMeasures.weightKg("194 lb")).isEqualTo(88);
        assertThat(BodyMeasures.weightKg("194")).isEqualTo(88);
        assertThat(BodyMeasures.weightKg("194lbs")).isEqualTo(88);
        assertThat(BodyMeasures.weightKg(" 194 LBS ")).isEqualTo(88);
    }

    @Test
    void weightIsRounded() {
        assertThat(BodyMeasures.weightKg("190 lbs")).isEqualTo(86); // 86.18
        assertThat(BodyMeasures.weightKg("175 lbs")).isEqualTo(79); // 79.38
        assertThat(BodyMeasures.weightKg("200 lbs")).isEqualTo(91); // 90.72
        assertThat(BodyMeasures.weightKg("185")).isEqualTo(84); // 83.92
    }

    @Test
    void unreadableWeightIsNull() {
        assertThat(BodyMeasures.weightKg(null)).isNull();
        assertThat(BodyMeasures.weightKg("")).isNull();
        assertThat(BodyMeasures.weightKg("heavy")).isNull();
        assertThat(BodyMeasures.weightKg("88 kg")).isNull();
        assertThat(BodyMeasures.weightKg("0 lbs")).isNull();
    }
}
