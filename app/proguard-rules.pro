# Ktor references JVM-only classes that are never used on Android.
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**
-dontwarn io.ktor.util.debug.**
-dontwarn javax.naming.**

# kotlinx.serialization: keep generated serializers.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class de.marcusleg.alertmanagerreceiver.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
