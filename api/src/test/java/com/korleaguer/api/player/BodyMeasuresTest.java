package com.korleaguer.api.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BodyMeasuresTest {

    @Test
    @DisplayName("피트·인치를 적는 꼴이 달라도 같은 센티미터 키가 나온다")
    void heightFormatsAllGiveTheSameCm() {
        // when // then
        // 6피트 2인치 = 74인치 = 187.96cm
        assertThat(BodyMeasures.heightCm("6' 2\"")).isEqualTo(188);
        assertThat(BodyMeasures.heightCm("6'2\"")).isEqualTo(188);
        assertThat(BodyMeasures.heightCm("6-2")).isEqualTo(188);
        assertThat(BodyMeasures.heightCm("6 ft 2 in")).isEqualTo(188);
        assertThat(BodyMeasures.heightCm("  6' 2\"  ")).isEqualTo(188);
    }

    @Test
    @DisplayName("키는 센티미터로 바꾼 뒤 반올림한다")
    void heightIsRounded() {
        // when // then
        assertThat(BodyMeasures.heightCm("5' 10\"")).isEqualTo(178); // 177.8
        assertThat(BodyMeasures.heightCm("6' 0\"")).isEqualTo(183); // 182.88
        assertThat(BodyMeasures.heightCm("5' 11\"")).isEqualTo(180); // 180.34
        assertThat(BodyMeasures.heightCm("6' 1\"")).isEqualTo(185); // 185.42
        // 정확히 190.5cm는 올림
        assertThat(BodyMeasures.heightCm("6' 3\"")).isEqualTo(191);
    }

    @Test
    @DisplayName("키가 비었거나 피트·인치로 적히지 않았으면 비워 둔다")
    void unreadableHeightIsNull() {
        // when // then
        assertThat(BodyMeasures.heightCm(null)).isNull();
        assertThat(BodyMeasures.heightCm("")).isNull();
        assertThat(BodyMeasures.heightCm("tall")).isNull();
        assertThat(BodyMeasures.heightCm("188")).isNull();
        assertThat(BodyMeasures.heightCm("188 cm")).isNull();
        assertThat(BodyMeasures.heightCm("6-12")).isNull(); // 인치는 11까지
    }

    @Test
    @DisplayName("파운드를 적는 꼴이 달라도 같은 킬로그램 몸무게가 나온다")
    void weightFormatsAllGiveTheSameKg() {
        // when // then
        // 194파운드 = 87.997kg
        assertThat(BodyMeasures.weightKg("194 lbs")).isEqualTo(88);
        assertThat(BodyMeasures.weightKg("194 lb")).isEqualTo(88);
        assertThat(BodyMeasures.weightKg("194")).isEqualTo(88);
        assertThat(BodyMeasures.weightKg("194lbs")).isEqualTo(88);
        assertThat(BodyMeasures.weightKg(" 194 LBS ")).isEqualTo(88);
    }

    @Test
    @DisplayName("몸무게는 킬로그램으로 바꾼 뒤 반올림한다")
    void weightIsRounded() {
        // when // then
        assertThat(BodyMeasures.weightKg("190 lbs")).isEqualTo(86); // 86.18
        assertThat(BodyMeasures.weightKg("175 lbs")).isEqualTo(79); // 79.38
        assertThat(BodyMeasures.weightKg("200 lbs")).isEqualTo(91); // 90.72
        assertThat(BodyMeasures.weightKg("185")).isEqualTo(84); // 83.92
    }

    @Test
    @DisplayName("몸무게가 비었거나 파운드가 아니거나 0이면 비워 둔다")
    void unreadableWeightIsNull() {
        // when // then
        assertThat(BodyMeasures.weightKg(null)).isNull();
        assertThat(BodyMeasures.weightKg("")).isNull();
        assertThat(BodyMeasures.weightKg("heavy")).isNull();
        assertThat(BodyMeasures.weightKg("88 kg")).isNull();
        assertThat(BodyMeasures.weightKg("0 lbs")).isNull();
    }
}
