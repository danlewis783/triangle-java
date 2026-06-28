package com.acme.triangle;

public final class DefaultImmutableTriangle implements ImmutableTriangle{

    // CCW corner vertex indices
    private final int a;
    private final int b;
    private final int c;

    // neighbour slot id across the edge opposite corner 0/1/2, or -1
    private final int n0;
    private final int n1;
    private final int n2;

    private final double attr;

    public DefaultImmutableTriangle(int a, int b, int c, int n0, int n1, int n2, double attr) {
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
    public int corner(int i) {
        return i == 0 ? a : i == 1 ? b : c;
    }

    /**
     * Neighbour slot id across the edge opposite corner {@code i}, or -1.
     */
    public int neighbor(int i) {
        return i == 0 ? n0 : i == 1 ? n1 : n2;
    }

    public int getA() {
        return a;
    }

    public int getB() {
        return b;
    }

    public int getC() {
        return c;
    }

    public int getN0() {
        return n0;
    }

    public int getN1() {
        return n1;
    }

    public int getN2() {
        return n2;
    }

    /**
     * region attribute (meaningful only when the mesh carries attrs)
     */
    public double getAttr() {
        return attr;
    }
}
