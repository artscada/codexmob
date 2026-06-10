package io.objectbox.relation
class ToOne<T> {
    var target: T? = null
    var targetId: Long = 0
    val isResolved: Boolean get() = false
    fun find(): T? = null
}
