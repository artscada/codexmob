package io.objectbox.query
class Query<T> {
    fun find(): List<T> = emptyList()
    fun findFirst(): T? = null
    fun findUnique(): T? = null
    fun findLazy(): List<T> = emptyList()
    fun count(): Long = 0L
    fun close() {}
}
