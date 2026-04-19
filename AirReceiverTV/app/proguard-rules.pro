# Garder les classes AirPlay (RTSP/RTP)
-keep class com.airreceiver.tv.** { *; }
# Garder jmDNS
-keep class javax.jmdns.** { *; }
-dontwarn javax.jmdns.**
# Garder Leanback
-keep class androidx.leanback.** { *; }
