# Relocate optimizable classes, including Guava and all of its bytecode callers,
# out of YunOS's system com.google.common namespace. Never add a Guava -keep rule.
-repackageclasses 'com.lizongying.mytv0.m17isolated'
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable

# Manifest entry points and AndroidX reflection rules are supplied by AAPT and
# dependency consumer rules. Gson configuration models use their original names.
-keep class com.lizongying.mytv0.data.** {
    *;
}

# LibVLC 3.6.5 has no consumer rules. Its .so looks up Java callbacks, fields,
# factories and nested descriptors by name, beyond methods declared native.
-keep class org.videolan.libvlc.** { *; }

# rtmp-client 3.2.0 also has no consumer rules. Retain both small JNI entry types.
-keep class io.antmedia.rtmp_client.RtmpClient { *; }
-keep class io.antmedia.rtmp_client.RTMPMuxer { *; }
