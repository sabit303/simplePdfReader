package com.simplepdf.reader.ui

internal class ByteLruCache<K, V>(
    val maxBytes: Int,
    private val sizeOf: (V) -> Int,
) {
    private data class Entry<V>(val value: V, val bytes: Int)
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    var sizeBytes = 0
        private set

    init {
        require(maxBytes > 0)
    }

    operator fun get(key: K): V? = entries[key]?.value

    fun put(key: K, value: V) {
        val bytes = sizeOf(value)
        require(bytes >= 0)
        if (bytes > maxBytes) return
        remove(key)
        trimToSize(maxBytes - bytes)
        entries[key] = Entry(value, bytes)
        sizeBytes += bytes
    }

    fun remove(key: K) {
        entries.remove(key)?.let { sizeBytes -= it.bytes }
    }

    fun removeWhere(predicate: (K) -> Boolean) {
        entries.keys.filter(predicate).forEach(::remove)
    }

    fun trimToSize(bytes: Int) {
        require(bytes >= 0)
        val iterator = entries.entries.iterator()
        while (sizeBytes > bytes && iterator.hasNext()) {
            sizeBytes -= iterator.next().value.bytes
            iterator.remove()
        }
    }

    fun clear() {
        entries.clear()
        sizeBytes = 0
    }
}
