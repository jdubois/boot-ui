package io.github.jdubois.bootui.engine.codepaths;

import java.util.Set;

/**
 * Which methods the code-paths sensor times ({@code docs/PLAN-v2.md} §5.14, M5-7a), as the agent's own filter decides:
 * the public and protected instance methods with code declared by the application's bean classes, except
 * {@code $}-prefixed, synthetic, and bridge methods and the {@code Object} methods applications override. A method it
 * does not time never shows in a call tree, so a call tree's silence says nothing about it.
 */
public final class TracedMethods {

    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_PROTECTED = 0x0004;
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_BRIDGE = 0x0040;
    private static final int ACC_NATIVE = 0x0100;
    private static final int ACC_ABSTRACT = 0x0400;
    private static final int ACC_SYNTHETIC = 0x1000;

    /** Why a method is not timed, said wherever a call tree's silence would otherwise read as evidence. */
    public static final String NOT_TRACED = "The code-paths sensor times only the public and protected instance"
            + " methods of the application's bean classes, never constructors, static, private, synthetic (lambda), or"
            + " bridge methods";

    private TracedMethods() {}

    /**
     * Whether the code-paths sensor times the method, or {@code null} when its access flags are unknown ({@code access}
     * negative). Record accessors and classes a framework leaves out, such as {@code @ConfigurationProperties} ones, are
     * not told apart here: the caller treats a missing call tree as unproven anyway.
     *
     * @param className the declaring class's binary name
     * @param name the method's name
     * @param descriptor its JVM descriptor
     * @param access its access flags from the class file, or a negative number when unknown
     * @param beanClasses the bean classes the sensor instruments in this run
     */
    public static Boolean traced(
            String className, String name, String descriptor, int access, Set<String> beanClasses) {
        if (beanClasses == null || !beanClasses.contains(className)) {
            return Boolean.FALSE;
        }
        if (name == null || name.startsWith("<") || name.startsWith("$")) {
            return Boolean.FALSE;
        }
        if (("equals".equals(name) && "(Ljava/lang/Object;)Z".equals(descriptor))
                || ("hashCode".equals(name) && "()I".equals(descriptor))
                || ("toString".equals(name) && "()Ljava/lang/String;".equals(descriptor))) {
            return Boolean.FALSE;
        }
        if (access < 0) {
            return null;
        }
        if ((access & (ACC_STATIC | ACC_BRIDGE | ACC_NATIVE | ACC_ABSTRACT | ACC_SYNTHETIC)) != 0) {
            return Boolean.FALSE;
        }
        return (access & (ACC_PUBLIC | ACC_PROTECTED)) != 0;
    }
}
