package com.acme.dispatch.exchange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.acme.dispatch.request.DeliveryContent;

/**
 * SHA-256 of the canonical form of the request content. Lets us recognise a carrier reply
 * "this operationId was already used with different data" (PAYLOAD_CONFLICT).
 */
public final class PayloadHasher {

    private PayloadHasher() {
    }

    public static String hash(DeliveryContent content) {
        // Length-prefixed fields: unambiguous whatever characters the values contain.
        // The weight is canonicalized here too: content read back from numeric(8,1) has scale 1.
        String canonical = field(content.cargoDescription())
                + field(DeliveryContent.canonicalWeight(content.weightKg()).toPlainString())
                + field(content.deliveryAddress())
                + field(content.deadline().toString());
        return sha256(canonical);
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String field(String value) {
        return value.length() + ":" + value + ";";
    }
}
