package com.acme.triangle;

/**
 * A region seed for constrained meshing: a {@link Point} {@code site} inside the
 * region, the {@code attribute} (region id) flooded to every triangle reachable
 * from the site without crossing a segment, and a {@code maxArea} quality bound
 * ({@code <= 0} means none).
 */
public final class Region {

    private final Point site;
    private final double attribute;
    private final double maxArea;

    public Region(Point site, double attribute, double maxArea) {
        this.site = site;
        this.attribute = attribute;
        this.maxArea = maxArea;
    }

    public Point getSite() {
        return site;
    }

    public double getAttribute() {
        return attribute;
    }

    public double getMaxArea() {
        return maxArea;
    }
}
