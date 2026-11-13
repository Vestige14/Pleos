# Pinned SpeechToText 3.3.1 privacy protection, proved on the reviewed device probe.
# Keep Car/Room/serialization and SDK callbacks inspectable while optimizing logger calls.
-dontshrink
-dontobfuscate
-assumenosideeffects class ai.pleos.playground.stt.utils.Log {
    public final void a(java.lang.String, java.lang.String);
    public final void b(java.lang.String, java.lang.String);
    public final void c(java.lang.String, java.lang.String);
    public final void d(java.lang.String, java.lang.String);
}

# Unused optional universal-access/Tink downloader references; ON_DEVICE never calls it.
-dontwarn com.google.api.client.http.GenericUrl
-dontwarn com.google.api.client.http.HttpHeaders
-dontwarn com.google.api.client.http.HttpRequest
-dontwarn com.google.api.client.http.HttpRequestFactory
-dontwarn com.google.api.client.http.HttpResponse
-dontwarn com.google.api.client.http.HttpTransport
-dontwarn com.google.api.client.http.javanet.NetHttpTransport$Builder
-dontwarn com.google.api.client.http.javanet.NetHttpTransport
-dontwarn org.joda.time.Instant
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
-dontwarn javax.annotation.concurrent.ThreadSafe
-dontwarn javax.lang.model.element.Modifier
