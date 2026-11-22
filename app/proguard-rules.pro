# Release builds remove every android.util.Log call, so nothing can end up in the system log by mistake.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static boolean isLoggable(java.lang.String, int);
}

# kotlinx.serialization, OkHttp, WorkManager and Compose ship their own consumer rules.
# The @Serializable classes of the addon format are kept by the serialization plugin's generated serializers.
-keepattributes *Annotation*, InnerClasses, Signature
