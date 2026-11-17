package com.screenocr.app

import android.content.Context

object Prefs {
    const val LANG_BOTH = "eng+urd"
    const val LANG_ENG = "eng"
    const val LANG_URD = "urd"

    private fun sp(c: Context) = c.getSharedPreferences("ocr", Context.MODE_PRIVATE)

    fun lang(c: Context): String = sp(c).getString("lang", LANG_BOTH) ?: LANG_BOTH
    fun setLang(c: Context, v: String) = sp(c).edit().putString("lang", v).apply()

    fun autoCopy(c: Context): Boolean = sp(c).getBoolean("autoCopy", false)
    fun setAutoCopy(c: Context, v: Boolean) = sp(c).edit().putBoolean("autoCopy", v).apply()
}
