package com.korleaguer.ingest;

import com.korleaguer.client.MlbGameContent;
import com.korleaguer.client.MlbPlayByPlay;
import com.korleaguer.domain.GameDetail.GameHighlight;
import com.korleaguer.domain.GameDetail.GamePlay;
import com.korleaguer.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 MLB 응답(src/test/resources/mlb, 2026-11-15 받음, 쓰는 칸만 남김)으로 경기 상세를 만든다.
 * - playbyplay-823898.json: /api/v1/game/823898/playByPlay(2026-09-18 SF@LAD). 이정후 4타석 + 다른 타석 하나
 * - content-823898.json: /api/v1/game/823898/content. 이정후 홈런 영상 + 다른 선수 영상 하나
 * - playbyplay-824410.json: /api/v1/game/824410/playByPlay(2026-07-21). 고우석이 상대한 타자 7명 + 다른 타석 하나
 * - content-824410.json: 같은 경기 영상(고우석이 나온 것은 없음)
 */
class GameDetailsTest {

    private static final long LEE = 808982;
    private static final long GO = 808970;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void hitterPlateAppearancesInKorean() throws IOException {
        List<GamePlay> plays = GameDetails.plays(read("playbyplay-823898.json", MlbPlayByPlay.class), LEE,
                Role.HITTER);

        assertThat(plays).extracting(GamePlay::inning).containsExactly(2, 3, 6, 9);
        assertThat(plays).extracting(GamePlay::resultKo)
                .containsExactly("홈런", "중견수 뜬공", "우익수 뜬공", "3루수 파울 뜬공");
        GamePlay homer = plays.getFirst();
        assertThat(homer.top()).isTrue();
        assertThat(homer.eventType()).isEqualTo("home_run");
        assertThat(homer.rbi()).isEqualTo(1);
        assertThat(homer.opponentName()).isEqualTo("Tyler Glasnow");
        assertThat(homer.balls()).isEqualTo(2);
        assertThat(homer.strikes()).isEqualTo(2);
        assertThat(homer.pitches()).isEqualTo(5);
        assertThat(homer.pitchKo()).isEqualTo("너클 커브");
        assertThat(homer.pitchKmh()).isEqualTo(131); // 81.5 mph
        assertThat(homer.exitKmh()).isEqualTo(157); // 97.7 mph
        assertThat(homer.launchAngle()).isEqualTo(33);
        assertThat(homer.distanceM()).isEqualTo(115); // 377 ft
        // 공 번호 모양(api.SavantClips.PLAY_ID가 받는 모양과 같다)
        assertThat(homer.playId()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(plays.getLast().opponentName()).isEqualTo("Edwin Díaz");
    }

    @Test
    void pitcherSeesEachBatterFaced() throws IOException {
        List<GamePlay> plays = GameDetails.plays(read("playbyplay-824410.json", MlbPlayByPlay.class), GO,
                Role.PITCHER);

        assertThat(plays).extracting(GamePlay::resultKo)
                .containsExactly("홈런", "2루타", "2루타", "헛스윙 삼진", "2루타", "루킹 삼진", "중견수 뜬공");
        assertThat(plays.getFirst().opponentName()).isEqualTo("Patrick Bailey");
        assertThat(plays.get(4).rbi()).isEqualTo(2);
        assertThat(plays).allMatch(p -> p.inning() == 7 && !p.top());
    }

    @Test
    void onlyThePlayersHighlightsWithMp4FirstChoice() throws IOException {
        List<GameHighlight> highlights = GameDetails.highlights(read("content-823898.json", MlbGameContent.class),
                LEE);

        assertThat(highlights).hasSize(1);
        GameHighlight homer = highlights.getFirst();
        assertThat(homer.title()).isEqualTo("Jung Hoo Lee's solo home run (10)");
        assertThat(homer.durationSec()).isEqualTo(30);
        assertThat(homer.mp4Url()).startsWith("https://mlb-cuts-diamond.mlb.com/").contains("1280x720").endsWith(".mp4");
        assertThat(homer.hlsUrl()).endsWith(".m3u8");
        assertThat(homer.imageUrl()).contains("w_960");

        assertThat(GameDetails.highlights(read("content-824410.json", MlbGameContent.class), GO)).isEmpty();
        assertThat(GameDetails.highlights(null, LEE)).isEmpty();
    }

    @Test
    void resultWordsForOtherOutcomes() {
        assertThat(ko("single", null, hit("line_drive", "9"), null)).isEqualTo("우전 안타");
        assertThat(ko("single", null, hit("ground_ball", "6"), null)).isEqualTo("내야 안타");
        assertThat(ko("field_out", "Some batter grounds out, shortstop to first.", hit("ground_ball", "6"), "SS"))
                .isEqualTo("유격수 땅볼");
        assertThat(ko("field_out", "Some batter lines out to second baseman.", hit("line_drive", "4"), "2B"))
                .isEqualTo("2루수 직선타");
        assertThat(ko("grounded_into_double_play", "", hit("ground_ball", "4"), "2B")).isEqualTo("2루수 병살타");
        assertThat(ko("walk", "", null, null)).isEqualTo("볼넷");
        assertThat(ko("hit_by_pitch", "", null, null)).isEqualTo("몸에 맞는 공");
        assertThat(ko("sac_fly", "", hit("fly_ball", "8"), "CF")).isEqualTo("희생 플라이");
        assertThat(ko("field_error", "", hit("ground_ball", "5"), "3B")).isEqualTo("3루수 실책 출루");
        assertThat(ko("something_new", "", null, null)).isEqualTo("Something New");
    }

    @Test
    void durationSeconds() {
        assertThat(GameDetails.seconds("00:00:30")).isEqualTo(30);
        assertThat(GameDetails.seconds("00:01:05")).isEqualTo(65);
        assertThat(GameDetails.seconds("bad")).isNull();
        assertThat(GameDetails.seconds(null)).isNull();
    }

    private static MlbPlayByPlay.HitData hit(String trajectory, String location) {
        return new MlbPlayByPlay.HitData(90.0, 10.0, 200.0, trajectory, location);
    }

    private static String ko(String eventType, String description, MlbPlayByPlay.HitData hit, String fielder) {
        MlbPlayByPlay.Event last = new MlbPlayByPlay.Event(true, "x", new MlbPlayByPlay.Details("X", null, null),
                null, hit);
        List<MlbPlayByPlay.Runner> runners = fielder == null ? List.of() : List.of(new MlbPlayByPlay.Runner(null,
                List.of(new MlbPlayByPlay.Credit(new MlbPlayByPlay.Position(fielder), "f_assist"))));
        String event = "something_new".equals(eventType) ? "Something New" : null;
        MlbPlayByPlay.Play play = new MlbPlayByPlay.Play(
                new MlbPlayByPlay.Result(event, eventType, description, 0), null, null, null, List.of(last), runners);
        return GameDetails.resultKo(play, last);
    }

    private <T> T read(String name, Class<T> type) throws IOException {
        try (InputStream in = new ClassPathResource("mlb/" + name).getInputStream()) {
            return json.readValue(in, type);
        }
    }
}
