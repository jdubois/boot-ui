package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.databaseadvisor.AbstractDriverRule.Outcome;
import io.github.jdubois.bootui.engine.databaseadvisor.PoolServerCompatibility.Result;
import java.util.Locale;
import java.util.Optional;

/**
 * The server settings the scan connection read for one datasource, as the server-setting rules see them. A read that
 * failed or was cut short is a coverage gap, never an empty answer: a setting that is not in a truncated read may
 * simply not have been returned.
 */
final class ServerSettings {

    private final VendorAugmentation<ServerSetting> augmentation;

    private ServerSettings(VendorAugmentation<ServerSetting> augmentation) {
        this.augmentation = augmentation;
    }

    /** The settings of {@code schema} read under {@code kind}, or the gap that explains why there are none. */
    static Result<ServerSettings> read(SchemaSnapshot schema, VendorFindingKind<ServerSetting> kind) {
        VendorAugmentation<ServerSetting> augmentation = schema.vendorFindings().augmentation(kind);
        if (!augmentation.available()) {
            return Result.unresolved(Outcome.gap(augmentation.reason()));
        }
        if (augmentation.truncated()) {
            return Result.unresolved(
                    Outcome.gap("the server settings read was truncated, so a setting may be missing from it."));
        }
        return Result.of(new ServerSettings(augmentation));
    }

    /** The setting named {@code name} when the server reported it with a value. */
    Optional<ServerSetting> get(String name) {
        return augmentation.findings().stream()
                .filter(setting -> name.equals(setting.name()) && setting.value() != null)
                .findFirst();
    }

    /** A whole number, or {@code null} when the value is not one. */
    static Long number(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** The server's own spelling of a boolean ({@code on}/{@code off}, {@code ON}/{@code OFF}, 1/0), or {@code null}. */
    static Boolean bool(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "on", "true", "1" -> Boolean.TRUE;
            case "off", "false", "0" -> Boolean.FALSE;
            default -> null;
        };
    }
}
