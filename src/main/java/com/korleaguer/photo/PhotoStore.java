package com.korleaguer.photo;

import com.korleaguer.config.DataMode;
import com.korleaguer.domain.Photo;
import com.korleaguer.domain.PhotoRepository;
import com.korleaguer.news.PoliteFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 선수 사진을 우리가 호스팅한다(원본에서 처음 한 번만 받는다).
 * <ul>
 *   <li>처음 요청되거나 주기 작업(refresh)이 돌 때 원본에서 받아 DB(photo)에 둔다. 그다음부터는 저장본을 준다.</li>
 *   <li>원본에 마지막으로 물어본 지 7일이 지나면 주기 작업이 조건부 요청(If-None-Match·If-Modified-Since)으로
 *       바뀌었는지만 묻는다. 304거나, 200이어도 본문(SHA-256)이 같으면 다시 쓰지 않는다.
 *       시즌이 정해진 사진(PhotoSources.seasonFixed)은 다시 묻지 않는다.</li>
 *   <li>원본 주소가 바뀌면(명단·포스팅 파일 수정) 새로 받는다.</li>
 *   <li>요청 예절은 PoliteFetcher(robots.txt, 사이트당 1초 간격, 실패하면 쉬기)를 따른다. 사진만 받는다(image/*, 2MB까지).</li>
 *   <li>샘플 모드는 네트워크를 쓰지 않는다(저장본이 없으면 사진 없음).</li>
 *   <li>우리가 만든 사진(원본 주소 classpath:…, 포스팅 선배 편집본)은 받지 않고 그 파일을 그대로 준다. 샘플 모드에서도 준다.</li>
 * </ul>
 */
@Component
public class PhotoStore {

    private static final Logger log = LoggerFactory.getLogger(PhotoStore.class);

    static final Duration RECHECK_AFTER = Duration.ofDays(7);
    static final int MAX_BYTES = 2 * 1024 * 1024;
    // webp를 먼저 달라고 한다(MLB 사진 서버는 Accept에 맞춰 형식을 고른다). avif는 오래된 사파리가 못 읽어 빼 둔다.
    private static final String ACCEPT = "image/webp,image/png;q=0.9,image/jpeg;q=0.9,image/*;q=0.8";

    private final PhotoSources sources;
    private final PhotoRepository photos;
    private final PoliteFetcher fetcher;
    private final DataMode dataMode;
    private final Clock clock;
    private final Object lock = new Object();
    private final Map<String, Photo> bundled = new ConcurrentHashMap<>();

    public PhotoStore(PhotoSources sources, PhotoRepository photos, @Qualifier("photoFetcher") PoliteFetcher fetcher,
                      DataMode dataMode, Clock clock) {
        this.sources = sources;
        this.photos = photos;
        this.fetcher = fetcher;
        this.dataMode = dataMode;
        this.clock = clock;
    }

    /** 저장본. 없거나 원본 주소가 바뀌었으면 지금 받아 저장한다. 목록에 없는 이름이거나 받을 수 없으면 빈 값 */
    public Optional<Photo> load(String key) {
        Optional<String> source = sources.source(key);
        if (source.isEmpty()) {
            return Optional.empty();
        }
        if (PhotoSources.bundled(source.get())) {
            return Optional.of(bundled.computeIfAbsent(key, k -> readBundled(k, source.get())));
        }
        Optional<Photo> stored = photos.findById(key);
        if (stored.isPresent() && stored.get().getSourceUrl().equals(source.get())) {
            return stored;
        }
        synchronized (lock) {
            Optional<Photo> again = photos.findById(key);
            if (again.isPresent() && again.get().getSourceUrl().equals(source.get())) {
                return again;
            }
            return download(key, source.get(), again.orElse(null), clock.instant()).or(() -> again);
        }
    }

    /** 주기 작업: 없는 사진은 받고, 물어본 지 7일 지난 사진은 바뀌었는지만 묻는다. 새로 받거나 바꾼 사진 수 */
    public int refresh() {
        if (dataMode == DataMode.SAMPLE) {
            return 0;
        }
        int changed = 0;
        synchronized (lock) {
            for (Map.Entry<String, String> entry : sources.all().entrySet()) {
                if (PhotoSources.bundled(entry.getValue())) {
                    continue; // 우리 파일이라 받을 것도 물을 것도 없다
                }
                Instant now = clock.instant();
                Optional<Photo> stored = photos.findById(entry.getKey());
                if (stored.isEmpty() || !stored.get().getSourceUrl().equals(entry.getValue())) {
                    if (download(entry.getKey(), entry.getValue(), stored.orElse(null), now).isPresent()) {
                        changed++;
                    }
                } else if (!PhotoSources.seasonFixed(entry.getKey())
                        && !now.isBefore(stored.get().getCheckedAt().plus(RECHECK_AFTER))) {
                    if (recheck(stored.get(), now)) {
                        changed++;
                    }
                }
            }
        }
        if (changed > 0) {
            log.info("photos: {} photo(s) fetched or updated", changed);
        }
        return changed;
    }

    /** 원본에서 받는다(조건 없이). 같은 사진이면 확인 시각만 바꾼다. */
    private Optional<Photo> download(String key, String source, Photo old, Instant now) {
        if (dataMode == DataMode.SAMPLE) {
            return Optional.empty();
        }
        Optional<PoliteFetcher.Fetched> fetched = fetcher.get(URI.create(source), Map.of("Accept", ACCEPT));
        if (fetched.isEmpty() || !fetched.get().modified() || !usable(fetched.get(), source)) {
            return Optional.empty();
        }
        return Optional.of(save(key, source, old, fetched.get(), now));
    }

    /** 바뀌었는지만 묻는다. 바뀌어서 본문을 바꿨으면 true */
    private boolean recheck(Photo photo, Instant now) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", ACCEPT);
        if (photo.getEtag() != null) {
            headers.put("If-None-Match", photo.getEtag());
        }
        if (photo.getLastModified() != null) {
            headers.put("If-Modified-Since", photo.getLastModified());
        }
        Optional<PoliteFetcher.Fetched> fetched = fetcher.get(URI.create(photo.getSourceUrl()), headers);
        if (fetched.isEmpty()) {
            return false; // 원본이 안 되면 저장본을 그대로 두고 다음에 다시 묻는다
        }
        if (!fetched.get().modified()) {
            photo.confirm(fetched.get().etag(), fetched.get().lastModified(), now);
            photos.save(photo);
            return false;
        }
        if (!usable(fetched.get(), photo.getSourceUrl())) {
            return false;
        }
        String before = photo.getSha256();
        save(photo.getPhotoKey(), photo.getSourceUrl(), photo, fetched.get(), now);
        return !before.equals(photo.getSha256());
    }

    private Photo save(String key, String source, Photo old, PoliteFetcher.Fetched fetched, Instant now) {
        String sha = sha256(fetched.body());
        Photo photo = old == null ? new Photo(key) : old;
        if (old != null && sha.equals(old.getSha256()) && source.equals(old.getSourceUrl())) {
            photo.confirm(fetched.etag(), fetched.lastModified(), now);
        } else {
            photo.replace(source, mediaType(fetched.contentType()), fetched.body(), sha, fetched.etag(),
                    fetched.lastModified(), now);
        }
        try {
            return photos.save(photo);
        } catch (DataIntegrityViolationException e) {
            return photos.findById(key).orElse(photo);
        }
    }

    /** 우리 파일(classpath:photos/….jpg)을 읽는다. 앱에 들어 있는 파일이라 DB에 두지 않고 메모리에만 둔다 */
    private Photo readBundled(String key, String source) {
        String resource = source.substring(PhotoSources.BUNDLED.length());
        try (InputStream in = PhotoStore.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("bundled photo is missing: " + resource);
            }
            byte[] data = in.readAllBytes();
            Photo photo = new Photo(key);
            photo.replace(source, "image/jpeg", data, sha256(data), null, null, clock.instant());
            return photo;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read bundled photo " + resource, e);
        }
    }

    private static boolean usable(PoliteFetcher.Fetched fetched, String source) {
        String type = mediaType(fetched.contentType());
        if (!type.startsWith("image/") || fetched.body().length == 0 || fetched.body().length >= MAX_BYTES) {
            log.warn("photos: {} is not a usable image ({}, {} bytes)", source, type, fetched.body().length);
            return false;
        }
        return true;
    }

    private static String mediaType(String contentType) {
        if (contentType == null) {
            return "application/octet-stream";
        }
        return contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
