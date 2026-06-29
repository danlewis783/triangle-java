package com.acme.triangle;

import com.google.common.collect.ImmutableList;

import java.util.List;

public final class PointUtils {

    private PointUtils() {
        // do not use
    }

    public static double[] flatten(List<Point> list) {
        int size = list.size();
        double[] xy = new double[size * 2];
        for (int i = 0; i < size; i++) {
            Point point = list.get(i);
            xy[2 * i] = point.getX();
            xy[2 * i + 1] = point.getY();
        }
        return xy;
    }

    public static ImmutableList<Point> toImmutableList(int size, double[] xy) {
        ImmutableList.Builder<Point> result = ImmutableList.builder();
        for (int i = 0; i < size; i++) {
            Point element = new Point(xy[2 * i], xy[2 * i + 1]);
            result.add(element);
        }
        return result.build();
    }
}
