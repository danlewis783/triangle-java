# Native Triangle library (Windows x86-64)

`triangle.dll` is the reduced, library-only Triangle compiled as a shared
library. JNA loads it from this classpath location
(`/win32-x86-64/triangle.dll`) for `NativeTriangleMesher`.

## Provenance

- repo: `triangle` (the reduced Triangle)
- build: `make shared` (clang `-O2 -DCPU86 -DNO_TIMER -shared
  -Wl,--export-all-symbols`)
- exports used: `triangulate`, `trifree`

Rebuild and re-copy if `triangle.c` changes. For other platforms, build the
corresponding shared library (`.so` / `.dylib`) and place it under the matching
JNA resource prefix (e.g. `linux-x86-64`, `darwin-aarch64`).
