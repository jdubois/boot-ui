package com.example.bootui.it.advisor;

import io.quarkus.arc.Unremovable;
import jakarta.inject.Singleton;

@Singleton
@Unremovable
public class DeclaredSharedState {
    public long publicState;
}
