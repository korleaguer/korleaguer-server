package com.korleaguer.api;

import com.korleaguer.domain.Photo;
import com.korleaguer.photo.PhotoStore;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * GET /api/photos/{이름}: 우리가 호스팅하는 선수 사진(PhotoStore). 명단·포스팅에 있는 사진만 준다(없으면 404).
 * 처음이면 그 자리에서 원본을 받아 저장한 뒤 준다. 브라우저가 하루 동안 저장해 두고, 그 뒤에는 ETag로 바뀌었는지만 묻는다.
 */
@RestController
public class PhotoController {

    // mlb-808982(지금 MLB 사진), kbo-…(KBO 사진), posting-673490-2024(포스팅 선배 편집본: 계약 구단 시절 MLB 사진, 흰 바탕)
    private static final Pattern KEY = Pattern.compile("mlb-\\d{1,10}|kbo-[a-z0-9-]{1,40}|posting-\\d{1,10}-\\d{4}");
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofDays(1)).cachePublic();

    private final PhotoStore store;

    public PhotoController(PhotoStore store) {
        this.store = store;
    }

    @GetMapping("/api/photos/{key}")
    public ResponseEntity<byte[]> photo(@PathVariable String key,
                                        @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        if (!KEY.matcher(key).matches()) {
            return ResponseEntity.notFound().build();
        }
        Optional<Photo> photo = store.load(key);
        if (photo.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String etag = "\"" + photo.get().getSha256() + "\"";
        if (ifNoneMatch != null && ifNoneMatch.contains(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(CACHE).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.get().getContentType()))
                .eTag(etag)
                .cacheControl(CACHE)
                .body(photo.get().getData());
    }
}
