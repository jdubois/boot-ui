package io.github.jdubois.bootui.engine.mysql;

import java.util.Locale;

/**
 * The server family behind MySQL Connector/J. MariaDB answers the driver as "MySQL", so only its version string
 * tells it apart. MariaDB is read on a best-effort basis and is not a supported server.
 */
enum MySqlFlavor {
    ORACLE_MYSQL,
    MARIADB;

    static boolean mariaDb(String reported) {
        return reported != null && reported.toLowerCase(Locale.ROOT).contains("mariadb");
    }

    /** A binary, no-pad collation for exact identifier keys; MariaDB has {@code utf8mb4_0900_bin} only from 11.4.5. */
    String binaryCollation() {
        return this == MARIADB ? "utf8mb4_nopad_bin" : "utf8mb4_0900_bin";
    }
}
