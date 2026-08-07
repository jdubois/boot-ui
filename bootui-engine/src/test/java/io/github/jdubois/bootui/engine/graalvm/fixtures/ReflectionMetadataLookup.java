package io.github.jdubois.bootui.engine.graalvm.fixtures;

public class ReflectionMetadataLookup {

    public Class<?>[] inspect(Class<?> type) {
        type.getRecordComponents();
        type.getPermittedSubclasses();
        type.getNestMembers();
        type.getDeclaredClasses();
        return new Class<?>[] {type.arrayType()};
    }
}
