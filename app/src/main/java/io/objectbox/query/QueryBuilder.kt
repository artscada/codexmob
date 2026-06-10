@file:Suppress("UNCHECKED_CAST", "TOO_MANY_ARGUMENTS")
package io.objectbox.query
class QueryBuilder<T> {
    class StringOrder { companion object { val CASE_SENSITIVE = StringOrder(); val CASE_INSENSITIVE = StringOrder() } }
    fun build(): Query<T> = Query()
    fun equal(property: Any? = null, value: Any? = null, order: StringOrder? = null): QueryBuilder<T> = this
    fun contains(property: Any? = null, value: Any? = null): QueryBuilder<T> = this
    fun contains(property: Any? = null, value: Any? = null, order: StringOrder? = null): QueryBuilder<T> = this
    fun notContains(property: Any? = null, value: Any? = null): QueryBuilder<T> = this
    fun startsWith(property: Any? = null, value: Any? = null): QueryBuilder<T> = this
    fun endsWith(property: Any? = null, value: Any? = null): QueryBuilder<T> = this
    fun greaterThan(property: Any? = null, value: Any? = null): QueryBuilder<T> = this
    fun lessThan(property: Any? = null, value: Any? = null): QueryBuilder<T> = this
    fun between(property: Any? = null, from: Any? = null, to: Any? = null): QueryBuilder<T> = this
    fun isNull(property: Any? = null): QueryBuilder<T> = this
    fun isNotNull(property: Any? = null): QueryBuilder<T> = this
    fun or(): QueryBuilder<T> = this
    fun and(): QueryBuilder<T> = this
    fun link(property: Any? = null): QueryBuilder<T> = this
    // ObjectBox's apply method - applies a filter condition
    fun apply(condition: Any?): QueryBuilder<T> = this
}
