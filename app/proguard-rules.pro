# Add classes here when necessary.

-dontwarn android.bluetooth.**
-dontwarn sun.misc.Unsafe
-dontwarn javax.lang.model.element.Modifier
-dontwarn java.lang.ClassValue
-dontwarn org.checkerframework.checker.**
-dontwarn afu.org.checkerframework.checker.**

-keep class com.winnyking.watchmouse.ui.devices.AvailableDevicesFragment {}
-keep class com.winnyking.watchmouse.ui.devices.NetworkTargetFragment {}
-keep class com.winnyking.watchmouse.ui.devices.AboutFragment {}
-keep class com.winnyking.watchmouse.ui.input.InputSettingsFragment {}

-keepclasseswithmembers class * {
    native <methods>;
}

-keepclasseswithmembers class com.winnyking.watchmouse.sensors.SensorFusionJni {
    *;
}
