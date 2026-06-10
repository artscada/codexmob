package io.objectbox
class BoxStore {
    fun close() {}
    fun runInTx(action: Runnable) { action.run() }
    fun runInTx(action: () -> Unit) { action() }
    fun <T> callInTx(action: () -> T): T = action()
}
