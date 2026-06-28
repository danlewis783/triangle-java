package com.acme.triangle;

/**
 * One triangle slot in an {@code IncrementalCdt} mesh: three CCW corner vertex
 * indices, the three neighbour slot ids across the opposite edges (or -1), and
 * the region attribute. Corner {@code j}'s opposite edge is
 * {@code (corner(j+1), corner(j+2))}, and {@code neighbor(j)} is the triangle
 * across it - the indexing the cavity surgery walks.
 * <p>
 * This is a <em>mutable arena cell</em>: corners and neighbour links are
 * rewritten in place as insertion relinks the local fan, so a reference is valid
 * only until the next insertion or split, exactly like the slot id it lives at.
 * Dead slots are a {@code null} entry in the triangle list, not a flag here.
 */
public final class Triangle implements MutableTriangle {

    // CCW corner vertex indices
    private final int a;
    private final int b;
    private final int c;

    // neighbour slot id across the edge opposite corner 0/1/2, or -1
    private int n0;
    private int n1;
    private int n2;

    private final double attr;

    public Triangle(int a, int b, int c, int n0, int n1, int n2, double attr) {
        this.a = a;
        this.b = b;
        this.c = c;
        this.n0 = n0;
        this.n1 = n1;
        this.n2 = n2;
        this.attr = attr;
    }

    /**
     * Corner vertex index at position {@code i} (0, 1, 2), for the (j+1)%3 walks.
     */
    @Override
    public int corner(int i) {
        return i == 0 ? a : i == 1 ? b : c;
    }

    /**
     * Neighbour slot id across the edge opposite corner {@code i}, or -1.
     */
    @Override
    public int neighbor(int i) {
        return i == 0 ? n0 : i == 1 ? n1 : n2;
    }

    @Override
    public void setNeighbor(int i, int id) {
        if (i == 0) {
            n0 = id;
        } else if (i == 1) {
            n1 = id;
        } else {
            n2 = id;
        }
    }

    @Override
    public int getA() {
        return a;
    }

    @Override
    public int getB() {
        return b;
    }

    @Override
    public int getC() {
        return c;
    }

    @Override
    public int getN0() {
        return n0;
    }

    @Override
    public void setN0(int n0) {
        this.n0 = n0;
    }

    @Override
    public int getN1() {
        return n1;
    }

    @Override
    public void setN1(int n1) {
        this.n1 = n1;
    }

    @Override
    public int getN2() {
        return n2;
    }

    @Override
    public void setN2(int n2) {
        this.n2 = n2;
    }

    /**
     * region attribute (meaningful only when the mesh carries attrs)
     */
    @Override
    public double getAttr() {
        return attr;
    }
}
