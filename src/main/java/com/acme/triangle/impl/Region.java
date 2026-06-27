package com.acme.triangle.impl;

/**
 * A region seed for constrained meshing: a {@link Point} {@code site} inside the
 * region, the {@code attribute} (region id) flooded to every triangle reachable
 * from the site without crossing a segment, and a {@code maxArea} quality bound
 * ({@code <= 0} means none).
 */
final class Region {

    final Point site;
    final double attribute;
    final double maxArea;

    Region(Point site, double attribute, double maxArea) {
        this.site = site;
        this.attribute = attribute;
        this.maxArea = maxArea;
    }
}
