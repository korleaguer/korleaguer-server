package com.korleaguer.photo;

import com.korleaguer.config.KorleaguerProperties;
import com.korleaguer.config.RosterProperties;
import com.korleaguer.posting.PostingCase;
import com.korleaguer.posting.PostingProperties;
import com.korleaguer.posting.Postings;
import com.korleaguer.support.PhotoFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 포스팅 선수 사진: 원본(photo-url)을 받지 않고 우리 편집본(흰 바탕 정사각형)을 사진 시즌이 든 이름으로 준다.
 * 편집본이 빠졌으면 서버가 뜨지 않는다(KBO 원본 94×118을 그대로 보이면 홈 76px 원에서 흐리고 지난 포스팅 선수 사진과 틀이 다르다).
 */
class PhotoSourcesTest {

    @Test
    @DisplayName("노시환 사진은 원본 대신 우리 편집본으로 준다")
    void realPostingFileServesTheEditedPhotoOfThisWintersPlayer() throws IOException {
        // given
        Postings postings = new Postings(load());
        PhotoSources sources = sources(postings, true);
        PostingCase roh = postings.current().getFirst();

        // when // then
        assertThat(sources.postingPath(roh)).isEqualTo("/photos/kbo-si-hwan-roh-2026?v=" + PhotoFiles.version("kbo-si-hwan-roh-2026.jpg"));
        assertThat(sources.source("kbo-si-hwan-roh-2026")).contains("classpath:photos/kbo-si-hwan-roh-2026.jpg");
        // 옛 이름(KBO 사진 서버 원본)은 목록에 없다: 하루 동안 저장해 둔 옛 사진이 새 사진 자리에 남지 않게 이름을 바꿨다
        assertThat(sources.source("kbo-si-hwan-roh")).isEmpty();
        assertThat(sources.all().values()).allSatisfy(source -> assertThat(source).doesNotContain("pstatic.net"));
    }

    @Test
    @DisplayName("사진 편집본이 없으면 빠진 파일을 알리며 멈춘다")
    void missingEditedPhotoStopsTheServer() {
        // given
        // 새 시즌 사진으로 photo-season만 바꾸고 편집본을 만들지 않은 경우
        Postings postings = postings(2025);

        // when // then
        assertThatThrownBy(() -> sources(postings, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("roster/posting.yml posting.current 노시환")
                .hasMessageContaining("core/src/main/resources/photos/kbo-si-hwan-roh-2025.jpg is missing")
                .hasMessageContaining("tools/posting-photo cut");
    }

    @Test
    @DisplayName("공식 사진을 끄면 편집본이 없어도 되고 사진 주소를 주지 않는다")
    void officialPhotosOffNeedsNoEditedFile() {
        // given
        Postings postings = postings(2025);

        // when
        PhotoSources sources = sources(postings, false);

        // then
        assertThat(sources.all()).isEmpty();
        assertThat(sources.postingPath(postings.current().getFirst())).isNull();
    }

    @Test
    @DisplayName("사진을 적지 않은 선수에게는 사진 주소가 없다")
    void playerWithoutPhotoHasNoPath() {
        // given
        Postings postings = new Postings(new PostingProperties(List.of(new PostingProperties.Current("노시환", null,
                "한화 이글스", "3루수", 2000, List.of(), "2026-11-02T08:00:00-05:00", "2026-12-01T17:00:00-05:00", null,
                null, null, null)), List.of()));

        // when // then
        assertThat(sources(postings, true).postingPath(postings.current().getFirst())).isNull();
    }

    private static Postings postings(int photoSeason) {
        return new Postings(new PostingProperties(List.of(new PostingProperties.Current("노시환", "si-hwan-roh",
                "한화 이글스", "3루수", 2000, List.of(), "2026-11-02T08:00:00-05:00", "2026-12-01T17:00:00-05:00", null,
                photoSeason, "https://img.example.kr/player/69737.png", null)), List.of()));
    }

    private static PhotoSources sources(Postings postings, boolean officialPhotos) {
        return new PhotoSources(new RosterProperties(List.of()), postings, new KorleaguerProperties(null, null, null,
                null, new KorleaguerProperties.Images(officialPhotos, null, null), null));
    }

    private static PostingProperties load() throws IOException {
        var sources = new YamlPropertySourceLoader().load("posting", new ClassPathResource("roster/posting.yml"));
        return new Binder(ConfigurationPropertySources.from(sources)).bind("posting", PostingProperties.class).get();
    }
}
