package com.acme.triangle.io;

import com.acme.triangle.TriangleMesherInput;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JSON reader/writer for benchmark fixtures and captured inputs.
 * <p>
 * The on-disk format is a versioned document wrapper around
 * `TriangleMesherInput`, not raw Java serialization. Reads validate the
 * document type, version, and DTO array/count consistency before returning
 * the input object.
 */
public final class TriangleJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private TriangleJson() {
    }

    public static TriangleMesherInput readInput(Path path) {
        TriangleMesherInputDocument doc = readInputDocument(path);
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
}