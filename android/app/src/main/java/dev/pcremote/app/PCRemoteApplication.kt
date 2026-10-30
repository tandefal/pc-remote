package dev.pcremote.app

import android.app.Application
import dev.pcremote.app.data.SecurePreferences

class PCRemoteApplication : Application() {
    val preferences by lazy { SecurePreferences(this) }
}
