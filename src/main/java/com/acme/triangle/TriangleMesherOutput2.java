package com.acme.triangle;

import com.google.common.collect.ImmutableList;

import java.util.List;

/**
 * Output of the Java meshing pipeline in its modelled form: a {@code List<Point>}
 * of vertices, the {@link ImmutableTriangle}s (corners, neighbour links and region
 * attribute), the recovered {@link Constraint} subsegments, and a
 * {@code hasAttributes} flag. The flag records whether the mesh carries region
 * attributes at all, distinguishing "no regions" from "regions whose attribute
 * is 0.0" - a distinction the per-triangle attribute value alone cannot make,
 * since both leave every triangle at 0.0. The points, triangles, and subsegments
 * are held as plain immutable lists, the same shape {@link TriangleMesherInput2}
 * carries its data in. The pipeline ({@code ConstrainedDelaunayTriangulator}
 * and {@code IncrementalCdt}) produces and threads this through directly; it is
 * marshalled back to the flat public {@link TriangleMesherOutput} via {@link
 * #toFlat} only at the {@link com.acme.triangle.TriangleMesher} boundary.
 */
public final class TriangleMesherOutput2 {

    private final ImmutableList<Point> points;
    private final ImmutableList<ImmutableTriangle> triangles;
    private final ImmutableList<Constraint> segments;
    private final boolean hasAttributes;

    public TriangleMesherOutput2(List<Point> points, List<ImmutableTriangle> triangles,
            List<Constraint> segments, boolean hasAttributes) {
        this.points = ImmutableList.copyOf(points);
        this.triangles = ImmutableList.copyOf(triangles);
        this.segments = ImmutableList.copyOf(segments);
        this.hasAttributes = hasAttributes;
    }

    public List<Point> getPoints() {
        return points;
    }

    public List<ImmutableTriangle> getTriangles() {
        return triangles;
    }

    public List<Constraint> getSegments() {
        return segments;
    }

    /**
     * Whether the mesh carries region attributes at all - false when no regions
     * were supplied, true even when every region present uses attribute 0.0. The
     * flat output records this as a null vs non-null {@code triangleAttributeList},
     * so the per-triangle 0.0 default is never mistaken for a meaningful attribute.
     */
    public boolean hasAttributes() {
        return hasAttributes;
    }

    /**
     * Repack a flat public {@link TriangleMesherOutput} into the modelled form -
     * the inverse of {@link #toFlat}. Lets a flat-native mesher (e.g. the JNA
     * adapter) return the modelled output by converting at its boundary. The
     * {@code hasAttributes} flag is recovered from whether the flat output carries
     * a {@code triangleAttributeList} at all, preserving the "no regions" vs
     * "region attribute 0.0" distinction.
     */
    public static TriangleMesherOutput2 from(TriangleMesherOutput out) {
        ImmutableList<Point> points = PointUtils.toImmutableList(out.numberOfPoints, out.pointList);

        int[] tri = out.triangleList;
        int[] neigh = out.neighborList;
        double[] attrs = out.triangleAttributeList;
        ImmutableList.Builder<ImmutableTriangle> triangles = ImmutableList.builder();
        for (int i = 0; i < out.numberOfTriangles; i++) {
            double attr = attrs != null ? attrs[i] : 0.0;
            int n0 = neigh != null ? neigh[3 * i] : -1;
            int n1 = neigh != null ? neigh[3 * i + 1] : -1;
            int n2 = neigh != null ? neigh[3 * i + 2] : -1;
            triangles.add(new DefaultImmutableTriangle(
                    tri[3 * i], tri[3 * i + 1], tri[3 * i + 2], n0, n1, n2, attr));
        }

        ImmutableList.Builder<Constraint> segments = ImmutableList.builder();
        int[] segList = out.segmentList;
        int[] segMarkers = out.segmentMarkerList;
        if (segList != null) {
            for (int i = 0; i < out.numberOfSegments; i++) {
                int marker = segMarkers != null ? segMarkers[i] : 0;
                segments.add(new Constraint(segList[2 * i], segList[2 * i + 1], marker));
            }
        }

        return new TriangleMesherOutput2(points, triangles.build(), segments.build(),
                out.triangleAttributeList != null);
    }

    /** Marshal back to the flat public {@link TriangleMesherOutput} at the API
     boundary - the inverse of repacking the input. */
    public TriangleMesherOutput toFlat() {
        TriangleMesherOutput result = new TriangleMesherOutput();
        result.numberOfPoints = points.size();
        result.pointList = PointUtils.flatten(points);

        int t = triangles.size();
        result.numberOfTriangles = t;
        int[] tri = new int[3 * t];
        int[] neigh = new int[3 * t];
        double[] attr = hasAttributes ? new double[t] : null;
        for (int i = 0; i < t; i++) {
            ImmutableTriangle triangle = triangles.get(i);
            tri[3 * i] = triangle.getA();
            tri[3 * i + 1] = triangle.getB();
            tri[3 * i + 2] = triangle.getC();
            neigh[3 * i] = triangle.getN0();
            neigh[3 * i + 1] = triangle.getN1();
            neigh[3 * i + 2] = triangle.getN2();
            if (attr != null) {
                attr[i] = triangle.getAttr();
            }
        }
        result.triangleList = tri;
        result.neighborList = neigh;
        result.triangleAttributeList = attr;

        int s = segments.size();
        result.numberOfSegments = s;
        int[] segList = new int[2 * s];
        int[] segMarkers = new int[s];
        for (int i = 0; i < s; i++) {
            Constraint c = segments.get(i);
            segList[2 * i] = c.getA();
            segList[2 * i + 1] = c.getB();
            segMarkers[i] = c.getMarker();
        }
        result.segmentList = segList;
        result.segmentMarkerList = segMarkers;
        return result;
    }
}
