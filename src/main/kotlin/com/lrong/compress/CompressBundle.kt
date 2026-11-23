package com.lrong.compress

import com.intellij.DynamicBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.CompressBundle"

/**
 * Message bundle accessor. English (`CompressBundle.properties`) is the
 * fallback language; `CompressBundle_zh_CN.properties` overrides it on a
 * Chinese locale.
 */
object CompressBundle : DynamicBundle(BUNDLE) {

    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
