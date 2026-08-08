# MuPDF's bundled native library resolves these generated Java bindings by name.
# Keep the JNI surface stable while still allowing method-body optimizations.
-keep,allowoptimization class com.artifex.mupdf.fitz.** { *; }
