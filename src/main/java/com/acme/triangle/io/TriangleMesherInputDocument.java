package com.acme.triangle.io;

import com.acme.triangle.TriangleMesherInput;
import java.util.ArrayList;
import java.util.List;

/**
 * Versioned JSON document for a mesher input fixture or captured case.
 */
public final class TriangleMesherInputDocument {

    public int formatVersion = 1;
    public String documentType = "triangle-mesher-input";
    public String name;
    public String description;
    public List<String> tags = new ArrayList<>();
    public TriangleMesherInput input;

    public TriangleMesherInputDocument() {
    }

    public static TriangleMesherInputDocument of(String name, TriangleMesherInput input) {
        TriangleMesherInputDocument d = new TriangleMesherInputDocument();
        d.name = name;
        d.input = copyOf(input);
        return d;
    }

    private static TriangleMesherInput copyOf(TriangleMesherInput in) {
        TriangleMesherInput c = new TriangleMesherInput();
        c.pointList = in.pointList != null ? in.pointList.clone() : new double[0];
        c.segmentList = in.segmentList != null ? in.segmentList.clone() : new int[0];
        c.segmentMarkerList = in.segmentMarkerList != null ? in.segmentMarkerList.clone() : new int[0];
        c.holeList = in.holeList != null ? in.holeList.clone() : new double[0];
        c.regionList = in.regionList != null ? in.regionList.clone() : new double[0];
        c.numberOfPoints = in.numberOfPoints;
        c.numberOfSegments = in.numberOfSegments;
        c.numberOfHoles = in.numberOfHoles;
        c.numberOfRegions = in.numberOfRegions;
        c.minAngleDegrees = in.minAngleDegrees;
        c.quiet = in.quiet;
        return c;
    }
}