package com.acme.triangle.impl;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.jspecify.annotations.Nullable;

/**
 * JNA binding to the native Triangle shared library ({@code triangle.dll} /
 * {@code .so}), loaded from the JNA classpath resource prefix
 * (e.g. {@code /win32-x86-64/triangle.dll}).
 */
interface TriangleLibrary extends Library {

    TriangleLibrary INSTANCE = Native.load("triangle", TriangleLibrary.class);

    /**
     * {@code void triangulate(char *switches, struct triangulateio *in,
     * struct triangulateio *out, struct triangulateio *vorout)}.
     * Triangle only reads {@code switches}; pass {@code null} for {@code vorout}.
     */
    void triangulate(String switches, TriangulateIO in, TriangulateIO out,
                     @Nullable TriangulateIO vorout);

    /** {@code void trifree(void *)} - frees an array Triangle allocated. */
    void trifree(Pointer memptr);
}
