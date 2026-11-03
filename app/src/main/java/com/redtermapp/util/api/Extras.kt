package com.redtermapp.util.api

import com.redtermapp.util.api.ApiRequestParser.ApiExtras
import com.redtermapp.util.api.ApiRequestParser.ApiRequest

/**
 * Guest scripts emit a fixed set of named extras, some of them empty when
 * the corresponding flag was not given. An empty string is "not set", not a
 * value: these treat it that way so the app can fall back to a default.
 */
fun ApiExtras.str(key: String): String? = strings[key]?.takeIf { it.isNotEmpty() }
fun ApiExtras.int(key: String): Int? = ints[key]
fun ApiExtras.bool(key: String): Boolean? = booleans[key]
fun ApiExtras.float(key: String): Float? = floats[key]
fun ApiRequest.str(key: String): String? = extras.str(key)
fun ApiRequest.int(key: String): Int? = extras.int(key)
fun ApiRequest.bool(key: String): Boolean? = extras.bool(key)
fun ApiRequest.float(key: String): Float? = extras.float(key)
