# Strip debug/verbose logging from release builds.
# R8 treats these calls as side-effect-free, removes them, and dead-code
# eliminates the argument expressions (string concatenation, format calls).
# Log.i / Log.w / Log.e are kept for real diagnostics.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
