# Consumer ProGuard rules for :core
# Gson relies on runtime reflection over @SerializedName fields in SheetPayload.
# If an app consuming :core enables R8/minification, these rules ensure the
# annotated fields survive shrinking. For the v1.0 CN release build, minify
# is disabled — these rules are a safety net for future releases.
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.pulseboard.core.SheetPayload { *; }
