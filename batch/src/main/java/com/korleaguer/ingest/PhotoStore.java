package com.korleaguer.ingest;

import com.korleaguer.config.DataMode;
import com.korleaguer.domain.Photo;
import com.korleaguer.domain.PhotoRepository;
import com.korleaguer.news.PoliteFetcher;
import com.korleaguer.photo.PhotoLoader;
import com.korleaguer.photo.PhotoSources;
import com.korleaguer.support.Hashes;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 선수 사진을 우리가 호스팅한다(원본에서 처음 한 번만 받는다).
 * <ul>
 *   <li>받기는 주기 작업(refresh, 뜰 때 한 번 + 10분마다)만 한다. 화면 요청(load)은 DB 저장본만 읽고 원본 사진 서버를 부르지 않는다
 *       (화면용 API는 DB만 읽는다, ARCHITECTURE.md §3). 저장본이 없으면 빈 값이다(웹은 이름 첫 글자 원).</li>
 *   <li>원본에 마지막으로 물어본 지 7일이 지나면 주기 작업이 조건부 요청(If-None-Match·If-Modified-Since)으로
 *       바뀌었는지만 묻는다. 304거나, 200이어도 본문(SHA-256)이 같으면 다시 쓰지 않는다.
 *       시즌이 정해진 사진(PhotoSources.seasonFixed)은 다시 묻지 않는다.</li>
 *   <li>원본 주소가 바뀌면(명단·포스팅 파일 수정) 주기 작업이 새로 받는다. 새로 받기 전까지, 또 받기에 실패해도 옛 저장본을 그대로 준다.</li>
 *   <li>받기에 실패해도 저장본을 지우지 않는다. 빠진 사진이 있으면 경고 로그를 남기고 다음 주기에 다시 받는다
 *       (원본이 실패로 답했으면 PoliteFetcher가 그 사이트를 쉬게 한 시간이 지난 뒤).</li>
 *   <li>주기 작업은 지금 목록에 없는 이름의 행(사진 이름을 바꾸고 남은 옛 행)을 지운다. 목록이 정상일 때만(공식 사진을 켰고 목록이
 *       비지 않았을 때) 지운다. 끄면 목록이 통째로 비므로 지우지 않는다(다시 켰을 때 받은 사진을 또 받지 않게).</li>
 *   <li>요청 예절은 PoliteFetcher(robots.txt, 사이트당 1초 간격, 실패하면 쉬기)를 따른다. 사진만 받는다(image/*, 2MB까지).</li>
 *   <li>샘플 모드는 네트워크를 쓰지 않는다(저장본이 없으면 사진 없음).</li>
 *   <li>우리가 만든 사진(원본 주소 classpath:…, 포스팅 선수·포스팅 선배·과거 소속팀 편집본)은 받지 않고 그 파일을
 *       그대로 준다. 샘플 모드에서도 준다.</li>
 * </ul>
 */
@Component
public class PhotoStore implements PhotoLoader {

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

    /**
     * 화면 요청용: DB 저장본만 읽는다(원본 사진 서버를 부르지 않는다). 원본 주소가 바뀌었어도 새로 받기 전까지는 옛 저장본을 준다.
     * 목록에 없는 이름이거나 아직 받지 못했으면 빈 값
     */
    @Override
    public Optional<Photo> load(String key) {
        Optional<String> source = sources.source(key);
        if (source.isEmpty()) {
            return Optional.empty();
        }
        if (PhotoSources.bundled(source.get())) {
            return Optional.of(bundled.computeIfAbsent(key, k -> readBundled(k, source.get())));
        }
        Optional<Photo> stored = photos.findById(key);
        stored.ifPresent(photo -> sources.remember(key, photo.getSha256()));
        return stored;
    }

    /** 서버가 뜰 때 저장된 사진의 해시를 읽어 둔다(주소의 ?v=가 처음부터 붙게). 못 읽어도 주기 작업·요청 때 채워진다 */
    @PostConstruct
    void rememberStoredHashes() {
        try {
            for (Object[] row : photos.findAllHashes()) {
                sources.remember((String) row[0], (String) row[1]);
            }
        } catch (RuntimeException e) {
            log.warn("photos: could not read stored hashes at startup", e);
        }
    }

    /**
     * 주기 작업: 없는 사진(원본 주소가 바뀐 사진 포함)은 받고, 물어본 지 7일 지난 사진은 바뀌었는지만 묻는다.
     * 받을 것도 물을 것도 없으면 원본을 부르지 않는다(10분마다 돌아도 원본 요청은 늘지 않는다). 새로 받거나 바꾼 사진 수
     */
    public int refresh() {
        if (dataMode == DataMode.SAMPLE) {
            return 0;
        }
        int changed = 0;
        List<String> missing = new ArrayList<>();
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
                    } else if (stored.isEmpty()) {
                        missing.add(entry.getKey()); // 화면에는 사진 대신 이름 첫 글자 원이 나간다
                    }
                } else {
                    sources.remember(entry.getKey(), stored.get().getSha256());
                    if (!PhotoSources.seasonFixed(entry.getKey())
                            && !now.isBefore(stored.get().getCheckedAt().plus(RECHECK_AFTER))
                            && recheck(stored.get(), now)) {
                        changed++;
                    }
                }
            }
            removeUnlisted();
        }
        if (changed > 0) {
            log.info("photos: {} photo(s) fetched or updated", changed);
        }
        if (!missing.isEmpty()) {
            log.warn("photos: {} photo(s) still missing, will retry on the next run: {}", missing.size(), missing);
        }
        return changed;
    }

    /** 지금 목록에 없는 이름의 행을 지운다. 목록이 비었으면(공식 사진을 껐으면) 아무것도 지우지 않는다 */
    private void removeUnlisted() {
        if (!sources.listUsable()) {
            return; // 끄면 목록이 비는 게 정상, 켰는데 비었으면 설정 문제: 어느 쪽이든 받아 둔 사진을 지우지 않는다
        }
        Map<String, String> listed = sources.all();
        List<String> unlisted = new ArrayList<>(new HashSet<>(photos.findAllKeys()));
        unlisted.removeIf(listed::containsKey);
        if (!unlisted.isEmpty()) {
            photos.deleteAllById(unlisted);
            log.info("photos: removed {} unlisted photo row(s): {}", unlisted.size(), unlisted);
        }
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
        String sha = Hashes.sha256(fetched.body());
        Photo photo = old == null ? new Photo(key) : old;
        if (old != null && sha.equals(old.getSha256()) && source.equals(old.getSourceUrl())) {
            photo.confirm(fetched.etag(), fetched.lastModified(), now);
        } else {
            photo.replace(source, mediaType(fetched.contentType()), fetched.body(), sha, fetched.etag(),
                    fetched.lastModified(), now);
        }
        Photo result;
        try {
            result = photos.save(photo);
        } catch (DataIntegrityViolationException e) {
            result = photos.findById(key).orElse(photo);
        }
        sources.remember(key, result.getSha256());
        return result;
    }

    /**
     * 우리 파일(classpath:photos/….jpg, 투명 바탕 편집본은 ….png)을 읽는다.
     * 앱에 들어 있는 파일이라 DB에 두지 않고 메모리에만 둔다
     */
    private Photo readBundled(String key, String source) {
        String resource = source.substring(PhotoSources.BUNDLED.length());
        try (InputStream in = PhotoStore.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("bundled photo is missing: " + resource);
            }
            byte[] data = in.readAllBytes();
            Photo photo = new Photo(key);
            String type = resource.endsWith(".png") ? "image/png" : "image/jpeg";
            photo.replace(source, type, data, Hashes.sha256(data), null, null, clock.instant());
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

}
