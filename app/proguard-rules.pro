# Compose, Media3, Coil and AndroidX ship their own consumer rules.
# The reader page calls these methods from JavaScript, so keep them by name.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
