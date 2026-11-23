package com.korleaguer.support;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 내용 해시(사진 본문의 sha256 등) */
public final class Hashes {

    private Hashes() {
    }

    /** 본문의 sha256(16진수 소문자 64자) */
    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
