package com.acme.triangle.io;

import com.acme.triangle.TriangleMesherOutput;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Versioned JSON document for a captured mesher output.
 */
public final class TriangleMesherOutputDocument {

    public int formatVersion = 1;
    public String documentType = "triangle-mesher-output";
    public @Nullable String name;
    public @Nullable String description;
    public List<String> tags = new ArrayList<>();
    public @Nullable TriangleMesherOutput output;

    public TriangleMesherOutputDocument() {
    }

    public static TriangleMesherOutputDocument of(String name, TriangleMesherOutput output) {
        TriangleMesherOutputDocument d = new TriangleMesherOutputDocument();
        d.name = name;
        d.output = copyOf(output);
        return d;
    }

    private static TriangleMesherOutput copyOf(TriangleMesherOutput out) {
        TriangleMesherOutput c = new TriangleMesherOutput();
        c.pointList = out.pointList != null ? out.pointList.clone() : new double[0];
        c.triangleList = out.triangleList != null ? out.triangleList.clone() : new int[0];
        c.neighborList = out.neighborList != null ? out.neighborList.clone() : new int[0];
        c.triangleAttributeList =
                out.triangleAttributeList != null ? out.triangleAttributeList.clone() : null;
        c.segmentList = out.segmentList != null ? out.segmentList.clone() : null;
        c.segmentMarkerList =
                out.segmentMarkerList != null ? out.segmentMarkerList.clone() : null;
        c.numberOfPoints = out.numberOfPoints;
        c.numberOfTriangles = out.numberOfTriangles;
        c.numberOfSegments = out.numberOfSegments;
        return c;
    }
}
