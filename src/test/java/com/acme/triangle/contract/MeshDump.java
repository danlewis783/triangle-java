package com.acme.triangle.contract;

import com.acme.triangle.TriangleMesherOutput;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Parses a golden mesh dump (the {@code <name>.txt} format written by the C
 * reference's golden_runner.c) into a {@link TriangleMesherOutput}. Lets the
 * Java {@link MeshValidator} be proven correct against known-good Triangle
 * meshes before any Java mesher exists. Edge lists in the dump are ignored
 * (the port does not produce them).
 */
final class MeshDump {

    private MeshDump() {
    }

    static TriangleMesherOutput load(String resource) {
        TriangleMesherOutput o = new TriangleMesherOutput();
        int na = 0;
        String section = null;

        for (String raw : readLines(resource)) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] t = line.split("\\s+");
            switch (t[0]) {
                case "numberofpoints":
                    o.numberOfPoints = Integer.parseInt(t[1]);
                    o.pointList = new double[2 * o.numberOfPoints];
                    break;
                case "numberofcorners":
                    break;
                case "numberoftriangles":
                    o.numberOfTriangles = Integer.parseInt(t[1]);
                    o.triangleList = new int[3 * o.numberOfTriangles];
                    break;
                case "numberoftriangleattributes":
                    na = Integer.parseInt(t[1]);
                    break;
                case "numberofsegments":
                    o.numberOfSegments = Integer.parseInt(t[1]);
                    break;
                case "numberofedges":
                    break;
                case "points":
                case "triangles":
                case "neighbors":
                case "segments":
                case "segmentmarkers":
                case "edges":
                    section = t[0];
                    if ("neighbors".equals(section)) {
                        o.neighborList = new int[3 * o.numberOfTriangles];
                    } else if ("segments".equals(section)) {
                        o.segmentList = new int[2 * o.numberOfSegments];
                    } else if ("segmentmarkers".equals(section)) {
                        o.segmentMarkerList = new int[o.numberOfSegments];
                    }
                    break;
                case "triangleattributes":
                    section = t[0];
                    if (na > 0) {
                        o.triangleAttributeList = new double[o.numberOfTriangles];
                    }
                    break;
                default:
                    fill(o, section, t);
            }
        }
        return o;
    }

    private static void fill(TriangleMesherOutput o, String section, String[] t) {
        int i = Integer.parseInt(t[0]);
        switch (section) {
            case "points":
                o.pointList[2 * i] = Double.parseDouble(t[1]);
                o.pointList[2 * i + 1] = Double.parseDouble(t[2]);
                break;
            case "triangles":
                o.triangleList[3 * i] = Integer.parseInt(t[1]);
                o.triangleList[3 * i + 1] = Integer.parseInt(t[2]);
                o.triangleList[3 * i + 2] = Integer.parseInt(t[3]);
                break;
            case "triangleattributes":
                o.triangleAttributeList[i] = Double.parseDouble(t[1]);   /* first */
                break;
            case "neighbors":
                o.neighborList[3 * i] = Integer.parseInt(t[1]);
                o.neighborList[3 * i + 1] = Integer.parseInt(t[2]);
                o.neighborList[3 * i + 2] = Integer.parseInt(t[3]);
                break;
            case "segments":
                o.segmentList[2 * i] = Integer.parseInt(t[1]);
                o.segmentList[2 * i + 1] = Integer.parseInt(t[2]);
                break;
            case "segmentmarkers":
                o.segmentMarkerList[i] = Integer.parseInt(t[1]);
                break;
            case "edges":
                break;                                                   /* ignored */
            default:
                throw new IllegalStateException("data row outside a section: "
                        + String.join(" ", t));
        }
    }

    private static Iterable<String> readLines(String resource) {
        try (InputStream in = MeshDump.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + resource);
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                java.util.List<String> lines = new java.util.ArrayList<>();
                String l;
                while ((l = r.readLine()) != null) {
                    lines.add(l);
                }
                return lines;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
