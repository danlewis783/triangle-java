package com.acme.triangle.impl;

final class TriangleMesherOutput2 {

    final Points points;

    final Triangles triangles;

    final Segments segments;

    public TriangleMesherOutput2(Points points, Triangles triangles, Segments segments) {
        this.points = points;
        this.triangles = triangles;
        this.segments = segments;
    }
}
