package com.korleaguer.ingest;

import com.korleaguer.client.MlbBoxscore;
import com.korleaguer.domain.Lineup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 선발·교체 규칙. 박스스코어는 MLB 실제 응답(game/823165/boxscore, LAD @ SF 2026-09-26)에서 몇 명만 남긴 것이다.
 */
class LineupRuleTest {

    private final MlbBoxscore box = read();

    @Test
    @DisplayName("타순 번호가 00으로 끝나는 타자는 선발이다")
    void battingOrderEndingIn00IsStarter() {
        // when // then
        assertThat(LineupRule.of(box, 813841)).isEqualTo(Lineup.STARTER); // SF 1번 타자 "100"
        assertThat(LineupRule.of(box, 605141)).isEqualTo(Lineup.STARTER); // LAD 1번 타자 "100"
    }

    @Test
    @DisplayName("타순 번호가 00으로 끝나지 않는 대타는 교체 출전이다")
    void otherBattingOrderIsSub() {
        // when // then
        assertThat(LineupRule.of(box, 701852)).isEqualTo(Lineup.SUB); // "801" 대타 후 포수
        assertThat(LineupRule.of(box, 678545)).isEqualTo(Lineup.SUB); // "602" 대타
        assertThat(LineupRule.of(box, 687221)).isEqualTo(Lineup.SUB); // LAD "301" 대타
    }

    @Test
    @DisplayName("팀에서 가장 먼저 던진 투수는 선발이고 그 뒤 투수는 교체다")
    void firstPitcherIsStarterOthersAreSub() {
        // when // then
        assertThat(LineupRule.of(box, 683363)).isEqualTo(Lineup.STARTER); // SF 첫 투수
        assertThat(LineupRule.of(box, 701474)).isEqualTo(Lineup.SUB);     // SF 두 번째 투수
        assertThat(LineupRule.of(box, 605483)).isEqualTo(Lineup.STARTER); // LAD 첫 투수
        assertThat(LineupRule.of(box, 656945)).isEqualTo(Lineup.SUB);     // LAD 마지막 투수
    }

    @Test
    @DisplayName("타순에 든 투타 겸업 선수는 타순대로 선발로 본다")
    void twoWayPlayerWhoBattedUsesBattingOrder() {
        // when // then
        assertThat(LineupRule.of(box, 660271)).isEqualTo(Lineup.STARTER); // 지명타자 "200"
    }

    @Test
    @DisplayName("벤치에 남았거나 박스스코어에 없으면 선발·교체를 정하지 않는다")
    void benchOrMissingIsNull() {
        // when // then
        assertThat(LineupRule.of(box, 681916)).isNull(); // 벤치(나오지 않음)
        assertThat(LineupRule.of(box, 1)).isNull();      // 박스스코어에 없음
        assertThat(LineupRule.of(null, 813841)).isNull();
    }

    private static MlbBoxscore read() {
        try (InputStream in = new ClassPathResource("mlb/boxscore-823165.json").getInputStream()) {
            return JsonMapper.builder().build().readValue(in, MlbBoxscore.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
