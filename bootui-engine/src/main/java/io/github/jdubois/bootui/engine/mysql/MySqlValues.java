package io.github.jdubois.bootui.engine.mysql;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.engine.support.CredentialRedaction;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.math.BigDecimal;
import java.math.BigInteger;

final class MySqlValues {
    private static final SecretMasker MASKER = new SecretMasker();

    private MySqlValues() {}

    static String counter(String value) {
        if (value == null) {
            return null;
        }
        try {
            BigInteger number = new BigInteger(value);
            return number.signum() < 0 ? null : number.toString();
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static Double number(String value) {
        if (value == null) {
            return null;
        }
        try {
            double number = new BigDecimal(value).doubleValue();
            return Double.isFinite(number) && number >= 0 ? number : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static Double millis(String picoseconds, boolean timed) {
        if (!timed || counter(picoseconds) == null) {
            return null;
        }
        return number(new BigDecimal(picoseconds).movePointLeft(9).toPlainString());
    }

    static String text(String value) {
        if (value == null) {
            return null;
        }
        String safe = CredentialRedaction.redact(value);
        safe = safe.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        return safe.length() <= 400 ? safe : safe.substring(0, 399) + "…";
    }

    static String exposed(String key, String value, Policy policy) {
        if (value == null) {
            return null;
        }
        if (policy.valueExposure() == ValueExposure.METADATA_ONLY) {
            return SecretMasker.MASKED_VALUE;
        }
        String safe = text(value);
        return policy.valueExposure() != ValueExposure.FULL && policy.maskSecrets()
                ? String.valueOf(MASKER.mask(key, safe))
                : safe;
    }

    static String digest(String value, Policy policy) {
        // DIGEST_TEXT is normalized by MySQL, not generic SQL input. Still redact before projection.
        return exposed("mysql.statement", value, policy);
    }

    record Policy(ValueExposure valueExposure, boolean maskSecrets) implements ExposurePolicy {
        static Policy of(ExposurePolicy policy) {
            ValueExposure mode = policy == null ? ValueExposure.MASKED : policy.valueExposure();
            return new Policy(mode == null ? ValueExposure.MASKED : mode, policy == null || policy.maskSecrets());
        }
    }
}
