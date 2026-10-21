package de.marcusleg.alertmanagerreceiver.data

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    @TypeConverter
    fun mapToString(map: Map<String, String>): String = Json.encodeToString(mapSerializer, map)

    @TypeConverter
    fun stringToMap(value: String): Map<String, String> =
        runCatching { Json.decodeFromString(mapSerializer, value) }.getOrDefault(emptyMap())
}
