package io.github.jdubois.bootui.engine.safety;

import java.util.Locale;
import java.util.Set;

/** Operator-owned safety settings must never come from the console's persisted overrides. */
public final class ConfigurationOverridePolicy {

    private static final Set<String> PROTECTED = Set.of(
            "bootuienabled",
            "bootuiallownonlocalhost",
            "bootuitrustcontainergateway",
            "bootuireadonly",
            "bootuiexposevalues",
            "bootuimasksecrets",
            "bootuiheapdumpallowrawdownload",
            "bootuipath",
            "bootuiapipath",
            "bootuioverridesfile",
            "bootuiactuatorexposedefaults",
            "managementendpointswebbasepath",
            "managementendpointsenabledbydefault",
            "managementendpointsaccessdefault",
            "managementendpointsaccessmaxpermitted",
            "managementserveraddress",
            "managementserverport",
            "managementserverbasepath");

    private static final Set<String> PROTECTED_LISTS = Set.of(
            "bootuienabledprofiles",
            "bootuidisabledprofiles",
            "bootuiallowedhosts",
            "bootuitrustedproxies",
            "springconfigname",
            "springconfiglocation",
            "springconfigadditionallocation",
            "springconfigimport");

    private ConfigurationOverridePolicy() {}

    public static boolean isProtected(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return PROTECTED.contains(normalized)
                || PROTECTED_LISTS.stream().anyMatch(key -> normalized.matches(key + "[0-9]*"))
                || normalized.startsWith("bootuiauthentication")
                || normalized.startsWith("springprofilesactive")
                || normalized.startsWith("springprofilesdefault")
                || normalized.startsWith("springprofilesinclude")
                || normalized.startsWith("springprofilesgroup")
                || normalized.startsWith("managementendpointswebexposure")
                || normalized.startsWith("managementendpointswebpathmapping")
                || normalized.startsWith("managementserverssl")
                || (normalized.startsWith("managementendpoint")
                        && (normalized.endsWith("access")
                                || normalized.endsWith("enabled")
                                || normalized.endsWith("showvalues")
                                || normalized.endsWith("showdetails")
                                || normalized.endsWith("showcomponents")
                                || normalized.endsWith("additionalpath")
                                || normalized.endsWith("addadditionalpaths")))
                || (normalized.startsWith("bootuipanels")
                        && (normalized.endsWith("enabled") || normalized.endsWith("readonly")));
    }

    public static void requireWritable(String name) {
        if (isProtected(name)) {
            throw new IllegalArgumentException("BootUI safety settings must be configured outside runtime overrides");
        }
    }
}
