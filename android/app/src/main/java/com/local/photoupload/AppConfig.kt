package com.local.photoupload

import android.content.Context
import java.util.Properties

object AppConfig {
    private const val PREFS = "upload_config"
    private const val KEY_SERVER = "server_url"
    private const val KEY_DIR = "save_dir"

    fun load(context: Context): Pair<String, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedServer = prefs.getString(KEY_SERVER, null)
        if (savedServer != null) {
            return savedServer to prefs.getString(KEY_DIR, "").orEmpty()
        }
        val props = Properties()
        context.assets.open("config.properties").use { input ->
            props.load(input.reader(Charsets.UTF_8))
        }
        return props.getProperty("server_url", "").trim() to props.getProperty("save_dir", "").trim()
    }

    fun save(context: Context, serverUrl: String, saveDir: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER, serverUrl)
            .putString(KEY_DIR, saveDir)
            .apply()
    }
}
