# WorkManager creates workers by reflection with the default WorkerFactory.
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# OkHttp optional platform integrations that are not on Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Ktor references JVM-only classes in code paths Android never takes.
-dontwarn java.lang.management.**
-dontwarn org.slf4j.impl.**
