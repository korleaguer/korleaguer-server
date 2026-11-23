package com.korleaguer.support;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 테스트용: 앱에 들어 있는 편집본 사진(core/src/main/resources/photos)의 주소 버전(sha256 앞 8자)을 파일 내용으로 계산한다 */
public final class PhotoFiles {

    private PhotoFiles() {
    }

    public static String version(String fileName) {
        try (InputStream in = PhotoFiles.class.getClassLoader().getResourceAsStream("photos/" + fileName)) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()))
                    .substring(0, 8);
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
