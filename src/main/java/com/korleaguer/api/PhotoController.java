package com.korleaguer.api;

import com.korleaguer.domain.Photo;
import com.korleaguer.photo.PhotoSources;
import com.korleaguer.photo.PhotoStore;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * GET /api/photos/{이름}: 우리가 호스팅하는 선수 사진(PhotoStore). 명단·포스팅에 있는 사진만 준다(없으면 404).
 * 처음이면 그 자리에서 원본을 받아 저장한 뒤 준다. 브라우저가 하루 동안 저장해 두고, 그 뒤에는 ETag로 바뀌었는지만 묻는다.
 * 주소에 ?v=(내용 해시 앞 8자)가 붙고 지금 내용과 같으면 내용이 바뀌면 주소도 바뀌므로 1년 immutable로 준다.
 * 버전이 없는 옛 주소나 지금 내용과 다른 버전은 하루짜리 그대로다(옛 주소도 계속 동작).
 */
@RestController
public class PhotoController {

    // mlb-808982(지금 MLB 사진), kbo-si-hwan-roh-2026(포스팅 선수 편집본: 현 소속 KBO 구단 공식 사진, 흰 바탕),
    // kbo-77829-2019(MLB 사진이 없는 포스팅 선배의 그 시즌 KBO 사진),
    // posting-673490-2024(포스팅 선배 편집본: 계약 구단 시절 MLB 사진, 흰 바탕),
    // career-67341-2023(선수 페이지 과거 소속팀 사진 편집본: MLB 사진과 같은 틀, 투명 바탕).
    // 웹(/photos/[key])도 같은 규칙이라 'kbo-' 뒤 40자를 넘는 이름은 쓰지 않는다(Postings가 slug를 35자로 막는다)
    private static final Pattern KEY = Pattern.compile(
            "mlb-\\d{1,10}|kbo-[a-z0-9-]{1,40}|posting-\\d{1,10}-\\d{4}|career-\\d{1,10}-\\d{4}");
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofDays(1)).cachePublic();
    private static final CacheControl VERSIONED = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

    private final PhotoStore store;

    public PhotoController(PhotoStore store) {
        this.store = store;
    }

    @GetMapping("/api/photos/{key}")
    public ResponseEntity<byte[]> photo(@PathVariable String key,
                                        @RequestParam(value = "v", required = false) String version,
                                        @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        if (!KEY.matcher(key).matches()) {
            return ResponseEntity.notFound().build();
        }
        Optional<Photo> photo = store.load(key);
        if (photo.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String etag = "\"" + photo.get().getSha256() + "\"";
        CacheControl cache = version != null && version.length() == PhotoSources.VERSION_LENGTH && photo.get().getSha256().startsWith(version)
                ? VERSIONED : CACHE;
        if (ifNoneMatch != null && ifNoneMatch.contains(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.get().getContentType()))
                .eTag(etag)
                .cacheControl(cache)
                .body(photo.get().getData());
    }
}
