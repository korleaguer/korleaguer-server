package com.korleaguer.api;

import com.korleaguer.api.GameDetailResponse.GameHighlight;
import com.korleaguer.api.GameDetailResponse.GamePlay;
import com.korleaguer.client.MlbGameContent;
import com.korleaguer.client.MlbPlayByPlay;
import com.korleaguer.domain.Role;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * MLB 타석별 기록(playByPlay)과 영상 목록(content)에서 선수 한 명의 경기 상세를 만든다(네트워크 없음).
 * 타자면 그 선수가 친 타석, 투수면 그 선수가 상대한 타자다. 결과는 한국 중계 말로 옮긴다('중견수 뜬공', '헛스윙 삼진', '우전 안타').
 * 숫자는 한국에서 쓰는 단위로 바꾼다(mph → km/h, 피트 → m).
 */
public final class GameDetails {

    /** 한 창에 넣는 하이라이트 수(그 선수가 나온 것만이라 보통 0~3개) */
    static final int MAX_HIGHLIGHTS = 6;

    private static final double KMH_PER_MPH = 1.609344;
    private static final double M_PER_FOOT = 0.3048;

    /** 타석을 끝내지 않은 결과(도루 실패·견제사 등으로 이닝이 끝나 타석이 이어지지 않음). 목록에서 뺀다 */
    private static final Set<String> NOT_PLATE_APPEARANCE = Set.of(
            "caught_stealing_2b", "caught_stealing_3b", "caught_stealing_home",
            "pickoff_1b", "pickoff_2b", "pickoff_3b",
            "pickoff_caught_stealing_2b", "pickoff_caught_stealing_3b", "pickoff_caught_stealing_home",
            "other_out", "runner_double_play", "stolen_base_2b", "stolen_base_3b", "stolen_base_home",
            "wild_pitch", "passed_ball", "balk", "game_advisory", "ejection", "injury");

    private static final Map<String, String> POSITIONS = Map.of(
            "P", "투수", "C", "포수", "1B", "1루수", "2B", "2루수", "3B", "3루수",
            "SS", "유격수", "LF", "좌익수", "CF", "중견수", "RF", "우익수");

    private static final Map<String, String> PITCH_TYPES = Map.ofEntries(
            Map.entry("FF", "직구"), Map.entry("FA", "직구"), Map.entry("FT", "투심"), Map.entry("SI", "싱커"),
            Map.entry("FC", "커터"), Map.entry("SL", "슬라이더"), Map.entry("ST", "스위퍼"), Map.entry("SV", "슬러브"),
            Map.entry("CU", "커브"), Map.entry("KC", "너클 커브"), Map.entry("CS", "슬로 커브"),
            Map.entry("CH", "체인지업"), Map.entry("FS", "스플리터"), Map.entry("FO", "포크볼"),
            Map.entry("SC", "스크루볼"), Map.entry("KN", "너클볼"), Map.entry("EP", "이퓨스"));

    private GameDetails() {
    }

    /**
     * 선수의 타석(투수면 상대 타자) 목록. 끝난 타석만, 경기 순서대로.
     *
     * @param mlbId 선수의 MLB 번호
     */
    public static List<GamePlay> plays(MlbPlayByPlay pbp, long mlbId, Role role) {
        if (pbp == null || pbp.allPlays() == null) {
            return List.of();
        }
        List<GamePlay> plays = new ArrayList<>();
        for (MlbPlayByPlay.Play play : pbp.allPlays()) {
            if (play == null || play.matchup() == null || play.result() == null) {
                continue;
            }
            MlbPlayByPlay.Person me = role == Role.PITCHER ? play.matchup().pitcher() : play.matchup().batter();
            if (me == null || me.id() == null || me.id() != mlbId) {
                continue;
            }
            if (play.about() != null && Boolean.FALSE.equals(play.about().isComplete())) {
                continue;
            }
            String eventType = play.result().eventType();
            if (eventType == null || NOT_PLATE_APPEARANCE.contains(eventType)) {
                continue;
            }
            plays.add(toPlay(play, role));
        }
        return plays;
    }

    private static GamePlay toPlay(MlbPlayByPlay.Play play, Role role) {
        List<MlbPlayByPlay.Event> pitches = play.playEvents() == null ? List.of()
                : play.playEvents().stream().filter(e -> Boolean.TRUE.equals(e.isPitch())).toList();
        MlbPlayByPlay.Event last = pitches.isEmpty() ? null : pitches.getLast();
        MlbPlayByPlay.HitData hit = last == null ? null : last.hitData();
        MlbPlayByPlay.Person opponent = role == Role.PITCHER ? play.matchup().batter() : play.matchup().pitcher();
        MlbPlayByPlay.About about = play.about();
        MlbPlayByPlay.Count count = play.count();
        return new GamePlay(
                about == null || about.inning() == null ? 0 : about.inning(),
                about != null && "top".equals(about.halfInning()),
                resultKo(play, last),
                play.result().eventType(),
                play.result().rbi() == null ? 0 : play.result().rbi(),
                opponent == null ? null : opponent.fullName(),
                count == null ? null : count.balls(),
                count == null ? null : count.strikes(),
                pitches.size(),
                last == null || last.details() == null ? null : pitchKo(last.details().type()),
                last == null || last.pitchData() == null ? null : kmh(last.pitchData().startSpeed()),
                hit == null ? null : kmh(hit.launchSpeed()),
                hit == null || hit.launchAngle() == null ? null : (int) Math.round(hit.launchAngle()),
                hit == null || hit.totalDistance() == null ? null : (int) Math.round(hit.totalDistance() * M_PER_FOOT),
                last == null ? null : last.playId());
    }

    /** 한국 중계 말로 옮긴 결과. 모르는 결과는 MLB 영어 이름 그대로 */
    static String resultKo(MlbPlayByPlay.Play play, MlbPlayByPlay.Event last) {
        String type = play.result().eventType();
        MlbPlayByPlay.HitData hit = last == null ? null : last.hitData();
        String fielder = fielder(play);
        String description = play.result().description() == null ? "" : play.result().description();
        return switch (type) {
            case "single" -> singleKo(hit);
            case "double" -> "2루타";
            case "triple" -> "3루타";
            case "home_run" -> "홈런";
            case "walk" -> "볼넷";
            case "intent_walk" -> "고의4구";
            case "hit_by_pitch" -> "몸에 맞는 공";
            case "strikeout" -> strikeoutKo(last);
            case "strikeout_double_play", "strikeout_triple_play" -> "삼진 병살";
            case "field_out", "force_out" -> outKo(fielder, hit, description);
            case "grounded_into_double_play" -> join(fielder, "병살타");
            case "grounded_into_triple_play", "triple_play" -> "삼중살";
            case "double_play" -> join(fielder, "병살");
            case "fielders_choice", "fielders_choice_out" -> "야수 선택";
            case "field_error" -> join(fielder, "실책 출루");
            case "sac_fly" -> "희생 플라이";
            case "sac_fly_double_play" -> "희생 플라이 병살";
            case "sac_bunt" -> "희생 번트";
            case "sac_bunt_double_play" -> "번트 병살";
            case "catcher_interf" -> "타격 방해 출루";
            case "batter_interference" -> "타자 방해 아웃";
            case "fan_interference" -> "관중 방해";
            default -> play.result().event() == null ? type : play.result().event();
        };
    }

    /** 단타: 외야 쪽이면 좌전·중전·우전, 내야면 내야 안타 */
    private static String singleKo(MlbPlayByPlay.HitData hit) {
        String location = hit == null ? null : hit.location();
        if (location == null) {
            return "안타";
        }
        return switch (location) {
            case "7" -> "좌전 안타";
            case "8" -> "중전 안타";
            case "9" -> "우전 안타";
            default -> "내야 안타";
        };
    }

    /** 삼진: 마지막 공이 루킹(C)이면 루킹 삼진, 헛스윙(S·W)이면 헛스윙 삼진, 그 밖(파울팁 등)은 삼진 */
    private static String strikeoutKo(MlbPlayByPlay.Event last) {
        String code = last == null || last.details() == null ? null : last.details().code();
        if ("C".equals(code)) {
            return "루킹 삼진";
        }
        if ("S".equals(code) || "W".equals(code)) {
            return "헛스윙 삼진";
        }
        return "삼진";
    }

    /** 인플레이 아웃: '유격수 땅볼', '중견수 뜬공', '2루수 직선타', '3루수 파울 뜬공' */
    private static String outKo(String fielder, MlbPlayByPlay.HitData hit, String description) {
        String trajectory = hit == null ? null : hit.trajectory();
        String kind = trajectory == null ? "아웃" : switch (trajectory) {
            case "ground_ball" -> "땅볼";
            case "line_drive" -> "직선타";
            case "fly_ball", "popup" -> "뜬공";
            case "bunt_grounder" -> "번트 땅볼";
            case "bunt_popup", "bunt_line_drive" -> "번트 뜬공";
            default -> "아웃";
        };
        if (description.contains("foul territory") && "뜬공".equals(kind)) {
            kind = "파울 뜬공";
        }
        return join(fielder, kind);
    }

    /** 공을 처음 잡은 수비수(수비 기록의 첫 줄) 한국어 이름. 모르면 null */
    private static String fielder(MlbPlayByPlay.Play play) {
        if (play.runners() == null) {
            return null;
        }
        return play.runners().stream()
                .filter(Objects::nonNull)
                .filter(r -> r.credits() != null)
                .flatMap(r -> r.credits().stream())
                .filter(c -> c != null && c.position() != null)
                .map(c -> POSITIONS.get(c.position().abbreviation()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static String join(String fielder, String kind) {
        return fielder == null ? kind : fielder + " " + kind;
    }

    private static String pitchKo(MlbPlayByPlay.PitchType type) {
        if (type == null) {
            return null;
        }
        String ko = type.code() == null ? null : PITCH_TYPES.get(type.code());
        return ko != null ? ko : type.description();
    }

    private static Integer kmh(Double mph) {
        return mph == null ? null : (int) Math.round(mph * KMH_PER_MPH);
    }

    /**
     * 그 선수가 나온 하이라이트 영상(keywordsAll의 player_id). 올라온 순서로, 많으면 MAX_HIGHLIGHTS개까지.
     * 주소는 MLB 서버 것을 그대로 둔다(mp4는 1280x720을 먼저 고른다).
     */
    public static List<GameHighlight> highlights(MlbGameContent content, long mlbId) {
        if (content == null || content.highlights() == null || content.highlights().highlights() == null
                || content.highlights().highlights().items() == null) {
            return List.of();
        }
        String id = String.valueOf(mlbId);
        return content.highlights().highlights().items().stream()
                .filter(Objects::nonNull)
                .filter(item -> item.keywordsAll() != null && item.keywordsAll().stream()
                        .anyMatch(k -> k != null && "player_id".equals(k.type()) && id.equals(k.value())))
                .sorted(Comparator.comparing(item -> item.date() == null ? "" : item.date()))
                .map(GameDetails::toHighlight)
                .filter(h -> h.mp4Url() != null || h.hlsUrl() != null)
                .limit(MAX_HIGHLIGHTS)
                .toList();
    }

    private static GameHighlight toHighlight(MlbGameContent.Item item) {
        List<MlbGameContent.Playback> playbacks = item.playbacks() == null ? List.of() : item.playbacks();
        String mp4 = playbacks.stream()
                .filter(p -> p != null && "mp4Avc".equals(p.name()) && p.url() != null)
                .map(MlbGameContent.Playback::url)
                .sorted(Comparator.comparing(url -> url.contains("1280x720") ? 0 : 1))
                .findFirst().orElse(null);
        String hls = playbacks.stream()
                .filter(p -> p != null && "hlsCloud".equals(p.name()) && p.url() != null)
                .map(MlbGameContent.Playback::url)
                .findFirst().orElse(null);
        String image = item.image() == null || item.image().cuts() == null ? null : item.image().cuts().stream()
                .filter(c -> c != null && c.src() != null && c.width() != null)
                .filter(c -> c.width() == 960 || c.width() == 640)
                .max(Comparator.comparingInt(MlbGameContent.Cut::width))
                .map(MlbGameContent.Cut::src)
                .orElse(null);
        return new GameHighlight(item.headline(), null, seconds(item.duration()), mp4, hls, image);
    }

    /** "00:00:30" → 30. 모양이 다르면 null */
    static Integer seconds(String duration) {
        if (duration == null || !duration.matches("\\d{1,2}:\\d{2}:\\d{2}")) {
            return null;
        }
        String[] parts = duration.split(":");
        return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]);
    }
}
