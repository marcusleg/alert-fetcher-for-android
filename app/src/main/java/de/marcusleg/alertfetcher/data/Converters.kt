package de.marcusleg.alertfetcher.data

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val listSerializer = ListSerializer(String.serializer())

    @TypeConverter
    fun mapToString(map: Map<String, String>): String = Json.encodeToString(mapSerializer, map)

    @TypeConverter
    fun stringToMap(value: String): Map<String, String> =
        runCatching { Json.decodeFromString(mapSerializer, value) }.getOrDefault(emptyMap())

    @TypeConverter
    fun listToString(list: List<String>): String = Json.encodeToString(listSerializer, list)

    @TypeConverter
    fun stringToList(value: String): List<String> =
        runCatching { Json.decodeFromString(listSerializer, value) }.getOrDefault(emptyList())
}
