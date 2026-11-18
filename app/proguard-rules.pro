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

# ML Kit (identificador de idioma): los ComponentRegistrar se instancian por reflexión desde el
# manifest (MlKitInitProvider). Sin conservar su constructor vacío, R8 lo elimina y
# LanguageIdentification.getClient() revienta en release con un NPE.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.** { <init>(); }

# Conserva números de línea para poder desofuscar trazas (el mapping.txt va en el AAB).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile