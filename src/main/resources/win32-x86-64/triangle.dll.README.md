# Native Triangle library (Windows x86-64)

`triangle.dll` is Jonathan Shewchuk's Triangle (pristine upstream source,
v1.6) compiled as a shared library. JNA loads it from this classpath location
(`/win32-x86-64/triangle.dll`) for `NativeTriangleMesher`.

## Provenance

- repo: `triangle`, branch `minimum-triangle-mods` (unmodified upstream
  `triangle.c`/`triangle.h` apart from the Win64 `uintptr_t` pointer-cast fix)
- build (LLVM-MinGW on `PATH`, from `C:\dev\triangle`):

  ```powershell
  mingw32-make shared
  ```

  which runs `cc -O2 -DCPU86 -DNO_TIMER -DTRILIBRARY -shared
  -Wl,--export-all-symbols -o triangle.dll triangle.c`. `-DTRILIBRARY` is
  required with the pristine source: it compiles the callable `triangulate()`
  entry point instead of the standalone `main()`. See `README-WINDOWS.md` in
  the triangle repo for details.
- exports used: `triangulate`, `trifree`

Rebuild and re-copy if `triangle.c` changes. For other platforms, build the
corresponding shared library (`.so` / `.dylib`) and place it under the matching
JNA resource prefix (e.g. `linux-x86-64`, `darwin-aarch64`).
