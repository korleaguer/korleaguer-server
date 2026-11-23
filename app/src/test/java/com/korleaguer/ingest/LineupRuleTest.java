package com.korleaguer.ingest;

import com.korleaguer.client.MlbBoxscore;
import com.korleaguer.domain.Lineup;
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
    void battingOrderEndingIn00IsStarter() {
        assertThat(LineupRule.of(box, 813841)).isEqualTo(Lineup.STARTER); // SF 1번 타자 "100"
        assertThat(LineupRule.of(box, 605141)).isEqualTo(Lineup.STARTER); // LAD 1번 타자 "100"
    }

    @Test
    void otherBattingOrderIsSub() {
        assertThat(LineupRule.of(box, 701852)).isEqualTo(Lineup.SUB); // "801" 대타 후 포수
        assertThat(LineupRule.of(box, 678545)).isEqualTo(Lineup.SUB); // "602" 대타
        assertThat(LineupRule.of(box, 687221)).isEqualTo(Lineup.SUB); // LAD "301" 대타
    }

    @Test
    void firstPitcherIsStarterOthersAreSub() {
        assertThat(LineupRule.of(box, 683363)).isEqualTo(Lineup.STARTER); // SF 첫 투수
        assertThat(LineupRule.of(box, 701474)).isEqualTo(Lineup.SUB);     // SF 두 번째 투수
        assertThat(LineupRule.of(box, 605483)).isEqualTo(Lineup.STARTER); // LAD 첫 투수
        assertThat(LineupRule.of(box, 656945)).isEqualTo(Lineup.SUB);     // LAD 마지막 투수
    }

    @Test
    void twoWayPlayerWhoBattedUsesBattingOrder() {
        assertThat(LineupRule.of(box, 660271)).isEqualTo(Lineup.STARTER); // 지명타자 "200"
    }

    @Test
    void benchOrMissingIsNull() {
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
