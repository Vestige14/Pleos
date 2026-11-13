# MV-02 prototype privacy protection; exact 3.3.1 AAR methods were inspected.
# Each method only validates arguments, builds a logging message and calls Log.
# Do not remove application diagnostics or any recognition/service callbacks.
-dontshrink
-dontobfuscate
-assumenosideeffects class ai.pleos.playground.stt.utils.Log {
    public final void a(java.lang.String, java.lang.String);
    public final void b(java.lang.String, java.lang.String);
    public final void c(java.lang.String, java.lang.String);
    public final void d(java.lang.String, java.lang.String);
}

# 3.3.1 transitively brings universal-access/Tink. Its optional HTTP key downloader
# is not called by this ON_DEVICE-only, no-registration probe. Exact missing
# references are listed instead of suppressing any package or all R8 warnings.
-dontwarn com.google.api.client.http.GenericUrl
-dontwarn com.google.api.client.http.HttpHeaders
-dontwarn com.google.api.client.http.HttpRequest
-dontwarn com.google.api.client.http.HttpRequestFactory
-dontwarn com.google.api.client.http.HttpResponse
-dontwarn com.google.api.client.http.HttpTransport
-dontwarn com.google.api.client.http.javanet.NetHttpTransport$Builder
-dontwarn com.google.api.client.http.javanet.NetHttpTransport
-dontwarn org.joda.time.Instant
# Compile-time annotation targets absent from Android's runtime library.
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
-dontwarn javax.annotation.concurrent.ThreadSafe
-dontwarn javax.lang.model.element.Modifier
