package org.mobicents.protocols.sctp.fstack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Resolves the sidecar binary and {@code libsctp_fstack.so} without hard-wiring one tree. */
final class FstackNativePaths {
    private FstackNativePaths() {}

    static Path sidecarBinary() {
        return firstExecutable(candidates("sctp.fstack.sidecar.bin", "sctp-fstack"));
    }

    static Path library() {
        return firstRegular(candidates("sctp.fstack.library", "libsctp_fstack.so"));
    }

    private static Path firstExecutable(List<Path> candidates) {
        for (Path path : candidates) {
            if (path != null && Files.isExecutable(path)) {
                return path;
            }
        }
        return null;
    }

    private static Path firstRegular(List<Path> candidates) {
        for (Path path : candidates) {
            if (path != null && Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    private static List<Path> candidates(String property, String fileName) {
        List<Path> out = new ArrayList<>();
        String configured = System.getProperty(property);
        if (configured != null && !configured.isBlank()) {
            out.add(Path.of(configured));
        }
        if ("libsctp_fstack.so".equals(fileName)) {
            out.add(Path.of("lib/libsctp_fstack.so"));
        }
        Path cwd = Path.of("").toAbsolutePath();
        out.add(cwd.resolve("sctp-native-fstack/build").resolve(fileName));
        out.add(cwd.resolve("../sctp-native-fstack/build").resolve(fileName));
        out.add(Path.of("/home/meodien/Desktop/ethiopia-working-dir/sctp/sctp-native-fstack/build")
                .resolve(fileName));
        return out;
    }
}
