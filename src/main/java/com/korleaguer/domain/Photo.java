package com.korleaguer.domain;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;

import java.time.Instant;

/**
 * 우리가 호스팅하는 선수 사진 한 장(PhotoStore). 원본에서 한 번 받아 두고, 원본이 바뀌었을 때만 본문을 바꾼다.
 * 기본키는 사진 이름(mlb-808982, kbo-si-hwan-roh)이다.
 */
@Entity
public class Photo {

    @Id
    private String photoKey;

    private String sourceUrl;
    private String contentType;

    @Lob
    @Basic(fetch = jakarta.persistence.FetchType.EAGER)
    @Column(columnDefinition = "mediumblob")
    private byte[] data;

    private String sha256;
    private String etag;
    private String lastModified;
    private Instant fetchedAt;
    private Instant checkedAt;

    protected Photo() {
    }

    public Photo(String photoKey) {
        this.photoKey = photoKey;
    }

    /** 원본에서 새로 받은 본문으로 바꾼다. */
    public void replace(String sourceUrl, String contentType, byte[] data, String sha256, String etag,
                       String lastModified, Instant now) {
        this.sourceUrl = sourceUrl;
        this.contentType = contentType;
        this.data = data;
        this.sha256 = sha256;
        this.etag = etag;
        this.lastModified = lastModified;
        this.fetchedAt = now;
        this.checkedAt = now;
    }

    /** 원본에 물어봤는데 바뀐 게 없다. 원본이 새로 준 확인값이 있으면 그것으로 바꾼다. */
    public void confirm(String etag, String lastModified, Instant now) {
        if (etag != null) {
            this.etag = etag;
        }
        if (lastModified != null) {
            this.lastModified = lastModified;
        }
        this.checkedAt = now;
    }

    public String getPhotoKey() {
        return photoKey;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getContentType() {
        return contentType;
    }

    public byte[] getData() {
        return data;
    }

    public String getSha256() {
        return sha256;
    }

    public String getEtag() {
        return etag;
    }

    public String getLastModified() {
        return lastModified;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }
}
