package io.objectbox.converter
interface PropertyConverter<T, D> {
    fun convertToEntityProperty(databaseValue: D?): T?
    fun convertToDatabaseValue(entityProperty: T?): D?
}
