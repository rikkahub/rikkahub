# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# keep kotlinx serializable classes
-keep @kotlinx.serialization.Serializable class * {*;}

# keep jlatexmath
-keep class org.scilab.forge.jlatexmath.** {*;}

-dontwarn com.google.re2j.**
-dontobfuscate

# Ktor 在 Android 上引用了仅 JVM 可用的 java.lang.management 类（IntellijIdeaDebugDetector）
# Android 不包含这些类，需要告知 R8 忽略
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# java.beans is not available on Android; Jackson references it only on JVM
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient

# auth0/jackson: TypeReference subclasses rely on runtime generic signatures.
# R8 strips Signature/InnerClasses/EnclosingMethod by default, and its class
# merging/inlining optimizations can destroy the anonymous class hierarchy that
# TypeReference.<init> depends on via getClass().getGenericSuperclass().
-keepattributes Signature, InnerClasses, EnclosingMethod
-keep class com.fasterxml.jackson.** { *; }
-keep class com.auth0.jwt.** { *; }

# JSch: SSH client loads crypto providers (com.jcraft.jsch.jce.*) reflectively
# from a static init table, so R8 can't see the dependency at compile time and
# strips them — release builds then crash with ClassNotFoundException on the
# first ssh_exec. Keep the whole package; it's small.
-keep class com.jcraft.jsch.** { *; }

# JSch ships optional integrations that reference desktop/JVM-only packages
# never present on Android — Pageant (Windows SSH agent → JNA + win32),
# Log4j2 logger backend, Kerberos GSS-API, and junixsocket Unix domain sockets.
# Our SSH tool path uses password/keyfile auth over TCP, so none of these are
# reachable at runtime. Tell R8 not to fail the build over the missing refs.
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**

# LiteRT-LM (com.google.ai.edge.litertlm:litertlm-android): the native side does
# JNI GetMethodID lookups by name against the Kotlin SamplerConfig / Conversation
# / Engine / etc. classes to read their fields. R8 strips/renames those methods
# on release, JNI gets a null jmethodID, and the next CallIntMethodV aborts the
# process with "JNI DETECTED ERROR IN APPLICATION: mid == null" the first time
# the user sends a message to a local LiteRT model. Keep the whole package; the
# Google AI Edge SDK is small and the cost is negligible vs. the crash.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.ai.edge.litert.** { *; }

# requery sqlite-android (forked as com.github.rikkahub:sqlite-android, used for the
# FTS-enabled SQLite that backs message search). Same shape as JSch + LiteRT-LM —
# the native SQLite shim does GetMethodID lookups by name on
# io.requery.android.database.*. JitPack-built AARs can ship inconsistent
# consumer-rules; explicit keeps make the next FTS query / DB open survive R8.
# Plus: DatabaseUtil.kt reflects on CursorWindow.sDefaultCursorWindowSize at startup
# to set the 32MB cursor window — R8 renaming that field would silently break it.
-keep class io.requery.android.database.** { *; }
-keepclasseswithmembers class io.requery.android.database.** {
    native <methods>;
}
-keepclassmembers class io.requery.android.database.CursorWindow {
    static int sDefaultCursorWindowSize;
}

# SLF4J 2.x service-loader binding. Without these rules the ServiceLoader<SLF4JServiceProvider>
# lookup can return empty in release (R8 keeps the META-INF/services file but the implementing
# class itself gets stripped) — Ktor / jmDNS / cron-utils logs then disappear, making future
# bug reports much harder. Cheap insurance.
-keep class * implements org.slf4j.spi.SLF4JServiceProvider
-keepclassmembers class * implements org.slf4j.spi.SLF4JServiceProvider { <init>(); }
-keep class uk.uuid.slf4j.android.** { *; }
