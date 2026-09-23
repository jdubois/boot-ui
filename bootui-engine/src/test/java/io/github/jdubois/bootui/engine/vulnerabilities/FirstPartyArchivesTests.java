package io.github.jdubois.bootui.engine.vulnerabilities;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FirstPartyArchivesTests {

    private static final List<String> BASE = List.of("com.boosting");

    @Test
    void moduleJarWhoseClassesAllLiveInTheBasePackageIsFirstParty() {
        assertThat(FirstPartyArchives.isFirstParty(
                        List.of(
                                "META-INF/",
                                "META-INF/MANIFEST.MF",
                                "com/",
                                "com/boosting/",
                                "com/boosting/cart/CartService.class",
                                "com/boosting/cart/package-info.class",
                                "com/boosting/Root.class",
                                "com/boosting/cart/messages.properties"),
                        BASE))
                .isTrue();
    }

    @Test
    void anyForeignClassDisqualifiesTheArchive() {
        assertThat(FirstPartyArchives.isFirstParty(
                        List.of("com/boosting/cart/CartService.class", "org/shaded/Library.class"), BASE))
                .isFalse();
        assertThat(FirstPartyArchives.isFirstParty(List.of("com/boostingother/Foo.class"), BASE))
                .isFalse();
        assertThat(FirstPartyArchives.isFirstParty(List.of("com/Foo.class"), BASE))
                .isFalse();
        assertThat(FirstPartyArchives.isFirstParty(List.of("Default.class"), BASE))
                .isFalse();
        assertThat(FirstPartyArchives.isFirstParty(
                        List.of("com/boosting/A.class", "META-INF/com/boosting/B.class"), BASE))
                .isFalse();
    }

    @Test
    void archiveWithoutApplicationClassesIsNeverFirstParty() {
        assertThat(FirstPartyArchives.isFirstParty(List.of("META-INF/MANIFEST.MF", "static/app.js"), BASE))
                .isFalse();
        assertThat(FirstPartyArchives.isFirstParty(List.of("module-info.class"), BASE))
                .isFalse();
        assertThat(FirstPartyArchives.isFirstParty(List.of(), BASE)).isFalse();
    }

    @Test
    void multiReleaseVariantsAndModuleDescriptorsAreAttributedToTheirPackage() {
        assertThat(FirstPartyArchives.isFirstParty(
                        List.of(
                                "module-info.class",
                                "com/boosting/A.class",
                                "META-INF/versions/21/com/boosting/A.class",
                                "META-INF/versions/21/module-info.class"),
                        BASE))
                .isTrue();
        assertThat(FirstPartyArchives.isFirstParty(
                        List.of("com/boosting/A.class", "META-INF/versions/21/org/other/B.class"), BASE))
                .isFalse();
        assertThat(FirstPartyArchives.isFirstParty(
                        List.of("com/boosting/A.class", "META-INF/versions/x/com/boosting/B.class"), BASE))
                .isFalse();
    }

    @Test
    void withoutBasePackagesNothingIsFirstParty() {
        assertThat(FirstPartyArchives.isFirstParty(List.of("com/boosting/A.class"), List.of()))
                .isFalse();
        assertThat(FirstPartyArchives.isFirstParty(
                        List.of("com/boosting/A.class"), FirstPartyArchives.basePackages(Arrays.asList(" ", null))))
                .isFalse();
        assertThat(FirstPartyArchives.basePackages(Arrays.asList(" com.boosting. ", "", null, "com.boosting")))
                .containsExactly("com.boosting");
        assertThat(FirstPartyArchives.basePackages(null)).isEmpty();
    }

    @Test
    void singleSegmentBasePackagesAreTooBroadToProveAnything() {
        assertThat(FirstPartyArchives.basePackages(List.of("com", "app", "com.boosting")))
                .containsExactly("com.boosting");
        assertThat(FirstPartyArchives.isFirstParty(
                        List.of("com/vendor/Library.class"), FirstPartyArchives.basePackages(List.of("com"))))
                .isFalse();
    }

    @Test
    void anArchiveBundlingAnotherArchiveIsNeverFirstParty() {
        assertThat(FirstPartyArchives.isFirstParty(
                        List.of("com/boosting/orders/Order.class", "lib/vendor-sdk.JAR"), BASE))
                .isFalse();
    }

    @Test
    void anArchiveCarryingMavenDescriptorsIsNeverFirstParty() {
        assertThat(FirstPartyArchives.isFirstParty(
                        List.of(
                                "com/boosting/internal/guava/Lists.class",
                                "META-INF/maven/com.google.guava/guava/pom.properties"),
                        BASE))
                .isFalse();
    }

    @Test
    void stopsReadingAtTheFirstForeignClass() {
        List<String> read = new ArrayList<>();
        Iterable<String> entries = () -> new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public String next() {
                String name = index++ == 0 ? "org/thirdparty/First.class" : "com/boosting/Later.class";
                read.add(name);
                return name;
            }
        };

        assertThat(FirstPartyArchives.isFirstParty(entries, BASE)).isFalse();
        assertThat(read).containsExactly("org/thirdparty/First.class");
    }

    @Test
    void oversizedArchivesAreConservativelyNotFirstParty() {
        List<String> entries = IntStream.rangeClosed(0, FirstPartyArchives.MAX_ENTRIES)
                .mapToObj(i -> "com/boosting/C" + i + ".class")
                .toList();

        assertThat(FirstPartyArchives.isFirstParty(entries, BASE)).isFalse();
        assertThat(FirstPartyArchives.isFirstParty(entries.subList(0, FirstPartyArchives.MAX_ENTRIES), BASE))
                .isTrue();
    }
}
