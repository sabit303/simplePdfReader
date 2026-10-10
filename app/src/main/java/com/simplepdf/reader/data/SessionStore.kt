package com.simplepdf.reader.data

import android.content.Context
import java.io.IOException

class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("reader_session", Context.MODE_PRIVATE)

    fun load(): ReaderSession {
        val encoded = preferences.getString("session", null) ?: return ReaderSession()
        return SessionCodec.decode(encoded)
    }

    fun save(session: ReaderSession) {
        if (!preferences.edit().putString("session", SessionCodec.encode(session)).commit()) {
            throw IOException("The reader session could not be saved")
        }
    }
}
