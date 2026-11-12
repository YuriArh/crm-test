package com.acme.carrier.execution;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * Deterministic carrier decision, so demo data and tests are reproducible:
 * <ul>
 *   <li>weight over 10 000 kg — rejected, "Превышен допустимый вес";</li>
 *   <li>address containing "тест-отказ" — rejected, "Адрес не обслуживается";</li>
 *   <li>otherwise succeeded: {@code TRK-} + 8 hex characters derived from the operationId,
 *       price {@code 300 + 25 × weight} rubles.</li>
 * </ul>
 */
public final class OutcomePolicy {

    static final BigDecimal MAX_WEIGHT_KG = new BigDecimal("10000");
    static final String REJECT_ADDRESS_MARKER = "тест-отказ";
    static final String OVERWEIGHT = "Превышен допустимый вес";
    static final String ADDRESS_NOT_SERVED = "Адрес не обслуживается";

    private static final BigDecimal BASE_PRICE = new BigDecimal("300");
    private static final BigDecimal PRICE_PER_KG = new BigDecimal("25");

    private OutcomePolicy() {
    }

    public static Outcome decide(UUID operationId, BigDecimal weightKg, String deliveryAddress) {
        if (weightKg.compareTo(MAX_WEIGHT_KG) > 0) {
            return Outcome.rejected(OVERWEIGHT);
        }
        if (deliveryAddress.toLowerCase(Locale.ROOT).contains(REJECT_ADDRESS_MARKER)) {
            return Outcome.rejected(ADDRESS_NOT_SERVED);
        }
        return Outcome.succeeded(trackingNumber(operationId), price(weightKg));
    }

    static String trackingNumber(UUID operationId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(operationId.toString().getBytes(StandardCharsets.UTF_8));
            return "TRK-" + HexFormat.of().withUpperCase().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static BigDecimal price(BigDecimal weightKg) {
        return BASE_PRICE.add(PRICE_PER_KG.multiply(weightKg)).setScale(2, RoundingMode.HALF_UP);
    }
}
