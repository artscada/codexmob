package io.objectbox.relation
class ToMany<T> : ArrayList<T>() {
    fun reset() {}
    val isResolved: Boolean get() = false
}
