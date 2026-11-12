package com.acme.carrier.operation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of the canonical operation content. Used to tell a repeat of the same operation from a different
 * operation reusing its {@code operationId}.
 */
final class ContentHash {

    private ContentHash() {
    }

    static String of(SubmitOperationRequest request) {
        String canonical = field(request.cargoDescription())
                + field(request.weightKg().toPlainString())
                + field(request.deliveryAddress())
                + field(request.deadline().toString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Length-prefixed, so field boundaries cannot shift ("ab"+"c" ≠ "a"+"bc"). */
    private static String field(String value) {
        return value.length() + ":" + value + ";";
    }
}
