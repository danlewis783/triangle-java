package com.acme.triangle;

/**
 * Facade for two-dimensional quality mesh generation.
 *
 * <p>This is the single type the consumer depends on. Implementations may be a
 * pure-Java port, a JNA adapter to the native C Triangle library, or a
 * validating/differential decorator over either. They are interchangeable: the
 * contract is a structurally valid constrained-Delaunay quality mesh, not a
 * byte-for-byte specific triangulation.
 */
public interface TriangleMesher {

    /**
     * Triangulate the given planar straight-line graph.
     *
     * @param input points, segments, holes, regions and meshing options
     * @return the resulting mesh (points, triangles, neighbours, segments, ...)
     */
    TriangleMesherOutput mesh(TriangleMesherInput input);
}
