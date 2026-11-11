package com.korleaguer.api;

import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.domain.Player;
import com.korleaguer.ingest.RosterProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RosterProfilesTest {

    private static final String TEMPLATE = "https://img.example.test/people/{mlbId}/headshot/current";

    private final RosterProperties roster = new RosterProperties(List.of(
            new RosterProperties.Entry("이정후", 808982L, "넥센·키움 히어로즈 (2017–2023)", null),
            new RosterProperties.Entry("김하성", null, null, null),
            new RosterProperties.Entry("고우석", 808970L, "LG 트윈스 (2017–2023)", "LG 트윈스")));

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

        assertThat(profiles.photoUrl(jungHoo)).isEqualTo("https://img.example.test/people/808982/headshot/current");
        assertThat(profiles.mlbUrl(jungHoo)).isEqualTo("https://www.mlb.com/player/808982");
        assertThat(profiles.careerKo(jungHoo)).isEqualTo("넥센·키움 히어로즈 (2017–2023)");
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
                new KorleaguerProperties.Images(officialPhotos, template), null);
        return new RosterProfiles(roster, properties);
    }
}
