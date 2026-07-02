package com.acme.triangle.io;

import com.acme.triangle.TriangleMesherInput;
import com.acme.triangle.TriangleMesherOutput;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JSON reader/writer for benchmark fixtures and captured cases.
 * <p>
 * The on-disk format is a versioned document wrapper around
 * `TriangleMesherInput` or `TriangleMesherOutput`, not raw Java
 * serialization. Reads validate the document type, version, and DTO
 * array/count consistency before returning the payload object.
 */
public final class TriangleJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private TriangleJson() {
    }

    public static TriangleMesherInput readInput(Path path) {
        TriangleMesherInputDocument doc = readInputDocument(path);
        if (doc.input == null) {
            throw new IllegalArgumentException("document has no input: " + path);
        }
        return doc.input;
    }

    public static TriangleMesherInputDocument readInputDocument(Path path) {
        try {
            TriangleMesherInputDocument doc =
                    MAPPER.readValue(path.toFile(), TriangleMesherInputDocument.class);
            validate(doc);
            return doc;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read triangle input JSON: " + path, e);
        }
    }

    public static void writeInput(Path path, TriangleMesherInput input, String name) {
        writeInputDocument(path, TriangleMesherInputDocument.of(name, input));
    }

    public static void writeInputDocument(Path path, TriangleMesherInputDocument doc) {
        validate(doc);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            MAPPER.writeValue(path.toFile(), doc);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write triangle input JSON: " + path, e);
        }
    }

    public static TriangleMesherOutput readOutput(Path path) {
        TriangleMesherOutputDocument doc = readOutputDocument(path);
        if (doc.output == null) {
            throw new IllegalArgumentException("document has no output: " + path);
        }
        return doc.output;
    }

    public static TriangleMesherOutputDocument readOutputDocument(Path path) {
        try {
            TriangleMesherOutputDocument doc =
                    MAPPER.readValue(path.toFile(), TriangleMesherOutputDocument.class);
            validate(doc);
            return doc;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read triangle output JSON: " + path, e);
        }
    }

    public static void writeOutput(Path path, TriangleMesherOutput output, String name) {
        writeOutputDocument(path, TriangleMesherOutputDocument.of(name, output));
    }

    public static void writeOutputDocument(Path path, TriangleMesherOutputDocument doc) {
        validate(doc);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            MAPPER.writeValue(path.toFile(), doc);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write triangle output JSON: " + path, e);
        }
    }

    private static void validate(TriangleMesherInputDocument doc) {
        if (doc == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        if (doc.formatVersion != 1) {
            throw new IllegalArgumentException("unsupported formatVersion: " + doc.formatVersion);
        }
        if (!"triangle-mesher-input".equals(doc.documentType)) {
            throw new IllegalArgumentException("unexpected documentType: " + doc.documentType);
        }
        if (doc.input == null) {
            throw new IllegalArgumentException("document input must not be null");
        }
        validate(doc.input);
    }

    private static void validate(TriangleMesherInput in) {
        double[] pointList = in.pointList != null ? in.pointList : new double[0];
        int[] segmentList = in.segmentList != null ? in.segmentList : new int[0];
        int[] segmentMarkerList = in.segmentMarkerList != null ? in.segmentMarkerList : new int[0];
        double[] holeList = in.holeList != null ? in.holeList : new double[0];
        double[] regionList = in.regionList != null ? in.regionList : new double[0];

        if (pointList.length != in.numberOfPoints * 2) {
            throw new IllegalArgumentException(
                    "pointList length " + pointList.length + " does not match numberOfPoints "
                            + in.numberOfPoints);
        }
        if (segmentList.length != in.numberOfSegments * 2) {
            throw new IllegalArgumentException(
                    "segmentList length " + segmentList.length + " does not match numberOfSegments "
                            + in.numberOfSegments);
        }
        if (segmentMarkerList.length != 0 && segmentMarkerList.length != in.numberOfSegments) {
            throw new IllegalArgumentException(
                    "segmentMarkerList length " + segmentMarkerList.length
                            + " does not match numberOfSegments " + in.numberOfSegments);
        }
        if (holeList.length != in.numberOfHoles * 2) {
            throw new IllegalArgumentException(
                    "holeList length " + holeList.length + " does not match numberOfHoles "
                            + in.numberOfHoles);
        }
        if (regionList.length != in.numberOfRegions * 4) {
            throw new IllegalArgumentException(
                    "regionList length " + regionList.length + " does not match numberOfRegions "
                            + in.numberOfRegions);
        }
    }

    private static void validate(TriangleMesherOutputDocument doc) {
        if (doc == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        if (doc.formatVersion != 1) {
            throw new IllegalArgumentException("unsupported formatVersion: " + doc.formatVersion);
        }
        if (!"triangle-mesher-output".equals(doc.documentType)) {
            throw new IllegalArgumentException("unexpected documentType: " + doc.documentType);
        }
        if (doc.output == null) {
            throw new IllegalArgumentException("document output must not be null");
        }
        validate(doc.output);
    }

    private static void validate(TriangleMesherOutput out) {
        double[] pointList = out.pointList != null ? out.pointList : new double[0];
        int[] triangleList = out.triangleList != null ? out.triangleList : new int[0];
        int[] neighborList = out.neighborList != null ? out.neighborList : new int[0];

        if (pointList.length != out.numberOfPoints * 2) {
            throw new IllegalArgumentException(
                    "pointList length " + pointList.length + " does not match numberOfPoints "
                            + out.numberOfPoints);
        }
        if (triangleList.length != out.numberOfTriangles * 3) {
            throw new IllegalArgumentException(
                    "triangleList length " + triangleList.length
                            + " does not match numberOfTriangles " + out.numberOfTriangles);
        }
        if (neighborList.length != out.numberOfTriangles * 3) {
            throw new IllegalArgumentException(
                    "neighborList length " + neighborList.length
                            + " does not match numberOfTriangles " + out.numberOfTriangles);
        }
        if (out.triangleAttributeList != null
                && out.triangleAttributeList.length != out.numberOfTriangles) {
            throw new IllegalArgumentException(
                    "triangleAttributeList length " + out.triangleAttributeList.length
                            + " does not match numberOfTriangles " + out.numberOfTriangles);
        }
        if (out.segmentList != null && out.segmentList.length != out.numberOfSegments * 2) {
            throw new IllegalArgumentException(
                    "segmentList length " + out.segmentList.length
                            + " does not match numberOfSegments " + out.numberOfSegments);
        }
        if (out.segmentMarkerList != null
                && out.segmentMarkerList.length != out.numberOfSegments) {
            throw new IllegalArgumentException(
                    "segmentMarkerList length " + out.segmentMarkerList.length
                            + " does not match numberOfSegments " + out.numberOfSegments);
        }
    }
}