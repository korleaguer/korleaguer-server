package com.korleaguer.api;

import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.domain.Player;
import com.korleaguer.ingest.RosterProperties;
import com.korleaguer.photo.PhotoSources;
import com.korleaguer.posting.PostingProperties;
import com.korleaguer.posting.Postings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RosterProfilesTest {

    private static final String TEMPLATE = "https://img.example.test/people/{mlbId}/headshot/current";
    private static final String KBO_TEMPLATE = "https://kbo.example.test/person/{season}/{kboId}.jpg";

    private final RosterProperties roster = new RosterProperties(List.of(
            new RosterProperties.Entry("이정후", 808982L, "넥센·키움 히어로즈 (2017–2023)", null, 67341L,
                    List.of(new RosterProperties.CareerPhoto("키움 히어로즈", "2017–2023", 2023))),
            new RosterProperties.Entry("김하성", null, null, null, null, null),
            new RosterProperties.Entry("고우석", 808970L, "LG 트윈스 (2017–2023)", "LG 트윈스", null, null)));

    @Test
    void returnedTeamComesFromTheRosterOnly() {
        RosterProfiles profiles = profiles(true, TEMPLATE);

        assertThat(profiles.returnedKo(new Player("고우석"))).isEqualTo("LG 트윈스");
        assertThat(profiles.returnedKo(new Player("이정후"))).isNull();
        assertThat(profiles.returnedKo(new Player("모르는 선수"))).isNull();
    }

    @Test
    void photoAndMlbLinkComeFromMlbId() {
        RosterProfiles profiles = profiles(true, TEMPLATE);
        Player jungHoo = new Player("이정후");

        // 사진은 우리가 호스팅하는 주소다(원본은 TEMPLATE, PhotoStore가 한 번 받아 둔다)
        assertThat(profiles.photoUrl(jungHoo)).isEqualTo("/photos/mlb-808982");
        assertThat(profiles.mlbUrl(jungHoo)).isEqualTo("https://www.mlb.com/player/808982");
        assertThat(profiles.careerKo(jungHoo)).isEqualTo("넥센·키움 히어로즈 (2017–2023)");
    }

    @Test
    void pastKboTeamPhotosAreOurHostedPaths() {
        RosterProfiles profiles = profiles(true, TEMPLATE);

        assertThat(profiles.careerPhotos(new Player("이정후"))).containsExactly(
                new PlayerPageResponse.CareerPhoto("키움 히어로즈", "2017–2023", "/photos/career-67341-2023"));
        assertThat(profiles.careerPhotos(new Player("김하성"))).isEmpty();
        assertThat(profiles(false, TEMPLATE).careerPhotos(new Player("이정후"))).isEmpty();
    }

    @Test
    void missingEditedPastTeamPhotoStopsTheServerFromStarting() {
        RosterProperties withNewPhoto = new RosterProperties(List.of(new RosterProperties.Entry("새 선수", 1L, null, null,
                1L, List.of(new RosterProperties.CareerPhoto("한화 이글스", "2019–2025", 2025)))));
        Postings none = new Postings(new PostingProperties(List.of(), List.of()));

        assertThatThrownBy(() -> new PhotoSources(withNewPhoto, none, new KorleaguerProperties(2026, null, null, null,
                new KorleaguerProperties.Images(true, TEMPLATE, KBO_TEMPLATE), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("새 선수")
                .hasMessageContaining("photos/career-1-2025.png")
                .hasMessageContaining("tools/posting-photo silo");
        // 공식 사진을 끄면 편집본도 필요 없다
        assertThat(new PhotoSources(withNewPhoto, none, new KorleaguerProperties(2026, null, null, null,
                new KorleaguerProperties.Images(false, TEMPLATE, KBO_TEMPLATE), null)).all()).isEmpty();
    }

    @Test
    void switchingPhotosOffKeepsTheMlbLink() {
        RosterProfiles profiles = profiles(false, TEMPLATE);
        Player jungHoo = new Player("이정후");

        assertThat(profiles.photoUrl(jungHoo)).isNull();
        assertThat(profiles.mlbUrl(jungHoo)).isEqualTo("https://www.mlb.com/player/808982");
    }

    @Test
    void blankTemplateGivesNoPhoto() {
        assertThat(profiles(true, "").photoUrl(new Player("이정후"))).isNull();
        assertThat(profiles(true, null).photoUrl(new Player("이정후"))).isNull();
    }

    @Test
    void playerWithoutMlbIdHasNoPhotoNorLink() {
        RosterProfiles profiles = profiles(true, TEMPLATE);
        Player haSeong = new Player("김하성");

        assertThat(profiles.photoUrl(haSeong)).isNull();
        assertThat(profiles.mlbUrl(haSeong)).isNull();
        assertThat(profiles.careerKo(haSeong)).isNull();
    }

    @Test
    void playerNotInRosterHasNothing() {
        RosterProfiles profiles = profiles(true, TEMPLATE);
        Player stranger = new Player("모르는 선수");

        assertThat(profiles.photoUrl(stranger)).isNull();
        assertThat(profiles.mlbUrl(stranger)).isNull();
        assertThat(profiles.careerKo(stranger)).isNull();
    }

    private RosterProfiles profiles(boolean officialPhotos, String template) {
        KorleaguerProperties properties = new KorleaguerProperties(2026, null, null, null,
                new KorleaguerProperties.Images(officialPhotos, template, KBO_TEMPLATE), null);
        return new RosterProfiles(roster, new PhotoSources(roster,
                new Postings(new PostingProperties(List.of(), List.of())), properties));
    }
}
