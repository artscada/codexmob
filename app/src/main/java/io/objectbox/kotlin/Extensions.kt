@file:Suppress("UNCHECKED_CAST", "TOO_MANY_ARGUMENTS")
package io.objectbox.kotlin
import io.objectbox.Box; import io.objectbox.BoxStore; import io.objectbox.query.QueryBuilder

inline fun <reified T> BoxStore.boxFor(): Box<T> = Box()
inline fun <reified T> Box<T>.query(): QueryBuilder<T> = QueryBuilder()
