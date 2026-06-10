@file:Suppress("UNCHECKED_CAST", "TOO_MANY_ARGUMENTS", "UNUSED_PARAMETER")
package io.objectbox

class Box<T> {
    fun put(entity: T): Long = 0L
    fun put(entities: Collection<T>) {}
    fun get(id: Any?): T? = null
    fun remove(entity: Any?) {}
    fun removeByIds(ids: Collection<Long>) {}
    fun remove(id: Long): Boolean = true
    fun removeAll() {}
    fun count(): Long = 0L
    val all: List<T> get() = emptyList()
    fun query(condition: Any? = null): io.objectbox.query.QueryBuilder<T> = io.objectbox.query.QueryBuilder()
}
