package io.github.jdubois.bootui.engine.codepaths;

import java.util.Locale;

/**
 * The application method that issued a recorded call, as {@code repeated-selects} names it ({@code docs/PLAN-v2.md}
 * §5.14, M5-4c): the method a code-paths stamp names, unless it is a repository or DAO method of the application's own,
 * in which case the first method above it in the route's tree that is not one, which issued the repository call.
 *
 * @param key the issuing method's key, {@code class#name+descriptor}
 * @param repositoryKey the repository or DAO method that ran the call, below the issuing method, or {@code null} when
 *     the stamp named the issuing method itself
 * @param repository whether the issuing method is itself a repository or DAO method, as when no method above it is
 *     known
 */
public record IssuingMethod(String key, String repositoryKey, boolean repository) {

    /** The method a stamp named, without a repository below it: a repository method's when {@code key} names one. */
    public static IssuingMethod of(String key) {
        return key == null ? null : new IssuingMethod(key, null, repositoryClass(classOf(key)));
    }

    /**
     * Whether {@code className} reads as a repository or DAO class by its name: a Spring Data custom implementation
     * ({@code *RepositoryImpl}), a {@code *Repository}, {@code *Repo}, {@code *Dao}, or {@code *DaoImpl} class, as
     * Panache repositories and {@code @Repository} classes usually are.
     */
    public static boolean repositoryClass(String className) {
        if (className == null) {
            return false;
        }
        String simple = className.substring(className.lastIndexOf('.') + 1);
        simple = simple.substring(simple.lastIndexOf('$') + 1).toLowerCase(Locale.ROOT);
        return simple.endsWith("repository")
                || simple.endsWith("repositoryimpl")
                || simple.endsWith("repo")
                || simple.endsWith("dao")
                || simple.endsWith("daoimpl");
    }

    /** The class of a method key, {@code class#name+descriptor}, or {@code null}. */
    static String classOf(String key) {
        int hash = key == null ? -1 : key.indexOf('#');
        return hash <= 0 ? null : key.substring(0, hash);
    }
}
