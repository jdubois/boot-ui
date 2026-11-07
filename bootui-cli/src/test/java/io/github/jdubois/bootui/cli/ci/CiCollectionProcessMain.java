package io.github.jdubois.bootui.cli.ci;

import java.nio.file.Path;

public final class CiCollectionProcessMain {

    private CiCollectionProcessMain() {}

    public static void main(String[] args) throws Exception {
        CiCollection.Window window =
                CiCollection.begin(Path.of(args[0]), args[1], "workflow:1", args[2], args[3], "/bootui/api", 5);
        System.out.println(window.attached() ? "ATTACHED" : window.attachFailureCode());
        System.exit(window.finish());
    }
}
