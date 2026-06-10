package io.objectbox.annotation
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.SOURCE)
annotation class Convert(val converter: kotlin.reflect.KClass<*>, val dbType: kotlin.reflect.KClass<*> = Int::class)
