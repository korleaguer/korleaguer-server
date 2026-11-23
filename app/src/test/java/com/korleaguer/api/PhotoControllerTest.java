package com.korleaguer.api;

import com.korleaguer.domain.Photo;
import com.korleaguer.domain.PhotoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/photos/{이름}: 저장해 둔 사진을 ETag·캐시 헤더와 함께 준다. 목록에 없거나 이상한 이름, 아직 받지 않은 사진은 404 (요청 중에는 원본을 받지 않는다) */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhotoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PhotoRepository photos;

    @BeforeEach
    void setUp() {
        Photo photo = new Photo("mlb-808982");
        photo.replace("https://img.mlbstatic.com/mlb-photos/image/upload/d_people:generic:headshot:silo:current.png/"
                        + "w_360,q_auto:best,f_auto/v1/people/808982/headshot/silo/current", "image/webp",
                "face".getBytes(StandardCharsets.UTF_8), "abc12345def", null, null, Instant.parse("2026-11-11T00:00:00Z"));
        photos.save(photo);
    }

    @Test
    void servesTheStoredPhotoWithCacheHeaders() throws Exception {
        mockMvc.perform(get("/api/photos/mlb-808982"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/webp"))
                .andExpect(header().string("ETag", "\"abc12345def\""))
                .andExpect(header().string("Cache-Control", "max-age=86400, public"))
                .andExpect(content().bytes("face".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void requestWithTheCurrentVersionIsCachedForeverAsImmutable() throws Exception {
        mockMvc.perform(get("/api/photos/mlb-808982").param("v", "abc12345"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
        mockMvc.perform(get("/api/photos/mlb-808982").param("v", "abc12345").header("If-None-Match", "\"abc12345def\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
    }

    @Test
    void oldAddressWithoutVersionOrWithAStaleVersionKeepsTheDayLongCache() throws Exception {
        mockMvc.perform(get("/api/photos/mlb-808982").param("v", "00000000"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=86400, public"));
    }

    @Test
    void sameEtagIsNotModified() throws Exception {
        mockMvc.perform(get("/api/photos/mlb-808982").header("If-None-Match", "\"abc12345def\""))
                .andExpect(status().isNotModified());
    }

    @Test
    void editedPhotoOfAPastPostingIsServedEvenInSampleMode() throws Exception {
        // posting.yml 김하성: 계약 구단(샌디에이고) 마지막 시즌(2024) MLB 사진의 편집본. 앱에 들어 있는 파일이라 샘플 모드에서도 준다
        byte[] edited;
        try (var in = getClass().getClassLoader().getResourceAsStream("photos/posting-673490-2024.jpg")) {
            edited = in.readAllBytes();
        }

        mockMvc.perform(get("/api/photos/posting-673490-2024"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().exists("ETag"))
                .andExpect(content().bytes(edited));
        mockMvc.perform(get("/api/photos/mlb-673490-2024")).andExpect(status().isNotFound()); // 원본은 더 주지 않는다
    }

    @Test
    void editedPastTeamPhotoIsATransparentPng() throws Exception {
        // baseball.yml 이정후 career-photos(키움 2023): MLB 배경 없는 사진과 같은 틀의 편집본. 샘플 모드에서도 준다
        byte[] edited;
        try (var in = getClass().getClassLoader().getResourceAsStream("photos/career-67341-2023.png")) {
            edited = in.readAllBytes();
        }

        mockMvc.perform(get("/api/photos/career-67341-2023"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(edited));
        mockMvc.perform(get("/api/photos/kbo-67341-2023")).andExpect(status().isNotFound()); // 원본(KBO 94×118)은 주지 않는다
        mockMvc.perform(get("/api/photos/career-67341-2019")).andExpect(status().isNotFound());
    }

    @Test
    void editedPhotoOfThePostingPlayerIsServedUnderTheSeasonName() throws Exception {
        // posting.yml 노시환: KBO 2026 공식 사진의 큰 판으로 만든 흰 바탕 256×256 편집본. 샘플 모드에서도 준다
        byte[] edited;
        try (var in = getClass().getClassLoader().getResourceAsStream("photos/kbo-si-hwan-roh-2026.jpg")) {
            edited = in.readAllBytes();
        }

        mockMvc.perform(get("/api/photos/kbo-si-hwan-roh-2026"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().exists("ETag"))
                .andExpect(header().string("Cache-Control", "max-age=86400, public"))
                .andExpect(content().bytes(edited));
        // 옛 이름(94×118 KBO 원본)은 목록에서 빠졌다. 하루 동안 저장해 둔 옛 사진이 새 주소에 섞이지 않게 이름을 바꿨다
        mockMvc.perform(get("/api/photos/kbo-si-hwan-roh")).andExpect(status().isNotFound());
    }

    @Test
    void listedPhotoNotStoredYetIsNotFound() throws Exception {
        // 명단(baseball.yml 김혜성)에 있지만 아직 받지 않은 사진: 요청 중에 원본을 받지 않고 404(웹은 이름 첫 글자 원)
        mockMvc.perform(get("/api/photos/mlb-808975")).andExpect(status().isNotFound());
    }

    @Test
    void unknownOrOddNamesAreNotFound() throws Exception {
        mockMvc.perform(get("/api/photos/mlb-1")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/photos/..%2Fsecret")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/photos/kbo-si-hwan-roh-2025")).andExpect(status().isNotFound());
    }
}
