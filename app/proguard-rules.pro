# kotlinx.serialization: keep generated serializers.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class de.marcusleg.alertfetcher.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
