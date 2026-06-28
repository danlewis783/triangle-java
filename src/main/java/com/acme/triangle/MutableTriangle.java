package com.acme.triangle;

public interface MutableTriangle extends ImmutableTriangle {
    void setNeighbor(int i, int id);

    void setN0(int n0);

    void setN1(int n1);

    void setN2(int n2);
}
