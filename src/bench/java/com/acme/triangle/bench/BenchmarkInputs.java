package com.acme.triangle.bench;

import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.io.TriangleJson;
import com.acme.triangle.io.TriangleMesherInputDocument;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads benchmark mesher inputs from JSON files.
 * <p>
 * Each file is expected to be a versioned `triangle-mesher-input` document.
 * The benchmark name comes from the document `name` when present, otherwise
 * from the file name without the `.json` suffix.
 */
public final class BenchmarkInputs {

    private BenchmarkInputs() {
    }

    public static List<NamedInput> loadDirectory(Path dir) {
        try {
            List<NamedInput> inputs = new ArrayList<>();
            try (Stream<Path> paths = Files.list(dir)) {
                paths.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .forEach(p -> inputs.add(loadOne(p)));
            }
            return inputs;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load benchmark inputs from " + dir, e);
        }
    }

    private static NamedInput loadOne(Path path) {
        TriangleMesherInputDocument doc = TriangleJson.readInputDocument(path);
        String fileName = path.getFileName().toString();
        String name = doc.name != null && !doc.name.trim().isEmpty()
                ? doc.name.trim()
                : stripJsonSuffix(fileName);
        TriangleMesherInput input = doc.input;
        return new NamedInput(name, input, path);
    }

    private static String stripJsonSuffix(String fileName) {
        return fileName.endsWith(".json")
                ? fileName.substring(0, fileName.length() - ".json".length())
                : fileName;
    }

    public static final class NamedInput {
        public final String name;
        public final TriangleMesherInput input;
        public final Path source;

        public NamedInput(String name, TriangleMesherInput input, Path source) {
            this.name = name;
            this.input = input;
            this.source = source;
        }
    }
}