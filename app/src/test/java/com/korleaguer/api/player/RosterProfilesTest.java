package com.korleaguer.api.player;


import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.config.RosterProperties;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.Team;
import com.korleaguer.photo.PhotoSources;
import com.korleaguer.posting.PostingProperties;
import com.korleaguer.posting.Postings;
import com.korleaguer.support.PhotoFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RosterProfilesTest {

    private static final String TEMPLATE = "https://img.example.test/people/{mlbId}/headshot/current";
    private static final String KBO_TEMPLATE = "https://kbo.example.test/person/{season}/{kboId}.jpg";

    private final RosterProperties roster = new RosterProperties(List.of(
            new RosterProperties.Entry("이정후", 808982L, "넥센·키움 히어로즈 (2017–2023)", null, 67341L,
                    List.of(new RosterProperties.CareerPhoto("키움 히어로즈", "2017–2023", 2023)), null),
            new RosterProperties.Entry("김하성", null, null, null, null, null, null),
            new RosterProperties.Entry("고우석", 808970L, "LG 트윈스 (2017–2023)", "LG 트윈스", null, null, null),
            new RosterProperties.Entry("옛 유니폼", 1L, null, null, null, null, " min ")));

    @Test
    @DisplayName("돌아간 국내 팀은 명단에 적어 둔 선수에게만 나온다")
    void returnedTeamComesFromTheRosterOnly() {
        // given
        RosterProfiles profiles = profiles(true, TEMPLATE);

        // when // then
        assertThat(profiles.returnedKo(new Player("고우석"))).isEqualTo("LG 트윈스");
        assertThat(profiles.returnedKo(new Player("이정후"))).isNull();
        assertThat(profiles.returnedKo(new Player("모르는 선수"))).isNull();
    }

    @Test
    @DisplayName("사진 속 팀은 명단 값, 없으면 지금 팀이고 사진이 없으면 비어 있다")
    void photoTeamIsTheRosterValueOrElseTheFallbackTeam() {
        // given
        RosterProfiles profiles = profiles(true, TEMPLATE);
        Team lad = new Team(119L);
        lad.update("LAD", "Los Angeles Dodgers");

        // when // then
        // 명단에 적은 값이 우선(공백·소문자는 정리), 없으면 지금 소속 팀
        assertThat(profiles.photoTeamAbbr(new Player("옛 유니폼"), lad)).isEqualTo("MIN");
        assertThat(profiles.photoTeamAbbr(new Player("이정후"), lad)).isEqualTo("LAD");
        // 사진이 없으면(MLB 선수 번호 없음, 사진 끔) 팀도 없다
        assertThat(profiles.photoTeamAbbr(new Player("김하성"), lad)).isNull();
        assertThat(profiles(false, TEMPLATE).photoTeamAbbr(new Player("이정후"), lad)).isNull();
    }

    @Test
    @DisplayName("MLB 선수 번호로 사진 주소와 MLB 선수 페이지 링크를 만든다")
    void photoAndMlbLinkComeFromMlbId() {
        // given
        RosterProfiles profiles = profiles(true, TEMPLATE);
        Player jungHoo = new Player("이정후");

        // when // then
        // 사진은 우리가 호스팅하는 주소다(원본은 TEMPLATE, PhotoStore가 한 번 받아 둔다)
        assertThat(profiles.photoUrl(jungHoo)).isEqualTo("/photos/mlb-808982");
        assertThat(profiles.mlbUrl(jungHoo)).isEqualTo("https://www.mlb.com/player/808982");
        assertThat(profiles.careerKo(jungHoo)).isEqualTo("넥센·키움 히어로즈 (2017–2023)");
    }

    @Test
    @DisplayName("과거 국내 팀 사진은 우리 주소로 나오고 사진을 끄면 비어 있다")
    void pastKboTeamPhotosAreOurHostedPaths() {
        // given
        RosterProfiles profiles = profiles(true, TEMPLATE);

        // when // then
        assertThat(profiles.careerPhotos(new Player("이정후"))).containsExactly(
                new PastTeamPhoto("키움 히어로즈", "2017–2023", "/photos/career-67341-2023?v=" + PhotoFiles.version("career-67341-2023.png")));
        assertThat(profiles.careerPhotos(new Player("김하성"))).isEmpty();
        assertThat(profiles(false, TEMPLATE).careerPhotos(new Player("이정후"))).isEmpty();
    }

    @Test
    @DisplayName("과거 팀 편집 사진이 없으면 서버 시작을 막고 빠진 파일을 알린다")
    void missingEditedPastTeamPhotoStopsTheServerFromStarting() {
        // given
        RosterProperties withNewPhoto = new RosterProperties(List.of(new RosterProperties.Entry("새 선수", 1L, null, null,
                1L, List.of(new RosterProperties.CareerPhoto("한화 이글스", "2019–2025", 2025)), null)));
        Postings none = new Postings(new PostingProperties(List.of(), List.of()));

        // when // then
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
    @DisplayName("공식 사진을 끄면 사진은 없어지고 MLB 선수 페이지 링크는 남는다")
    void switchingPhotosOffKeepsTheMlbLink() {
        // given
        RosterProfiles profiles = profiles(false, TEMPLATE);
        Player jungHoo = new Player("이정후");

        // when // then
        assertThat(profiles.photoUrl(jungHoo)).isNull();
        assertThat(profiles.mlbUrl(jungHoo)).isEqualTo("https://www.mlb.com/player/808982");
    }

    @Test
    @DisplayName("사진 원본 주소를 정하지 않으면 선수 사진이 나오지 않는다")
    void blankTemplateGivesNoPhoto() {
        // when // then
        assertThat(profiles(true, "").photoUrl(new Player("이정후"))).isNull();
        assertThat(profiles(true, null).photoUrl(new Player("이정후"))).isNull();
    }

    @Test
    @DisplayName("MLB 선수 번호가 없으면 사진과 MLB 링크가 나오지 않는다")
    void playerWithoutMlbIdHasNoPhotoNorLink() {
        // given
        RosterProfiles profiles = profiles(true, TEMPLATE);
        Player haSeong = new Player("김하성");

        // when // then
        assertThat(profiles.photoUrl(haSeong)).isNull();
        assertThat(profiles.mlbUrl(haSeong)).isNull();
        assertThat(profiles.careerKo(haSeong)).isNull();
    }

    @Test
    @DisplayName("명단에 없는 선수는 사진·MLB 링크·국내 경력이 모두 비어 있다")
    void playerNotInRosterHasNothing() {
        // given
        RosterProfiles profiles = profiles(true, TEMPLATE);
        Player stranger = new Player("모르는 선수");

        // when // then
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
