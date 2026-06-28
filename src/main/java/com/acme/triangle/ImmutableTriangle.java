package com.acme.triangle;

public interface ImmutableTriangle {
    int corner(int i);

    int neighbor(int i);

    int getA();

    int getB();

    int getC();

    int getN0();

    int getN1();

    int getN2();

    double getAttr();
}
