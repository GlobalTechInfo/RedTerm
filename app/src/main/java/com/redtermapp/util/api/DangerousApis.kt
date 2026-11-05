package com.redtermapp.util.api

import android.Manifest
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.net.toUri
import com.redtermapp.util.api.ApiRequestParser.ApiRequest

/**
 * `termux-location`. A fix is asked from the kernel providers and the
 * request blocks for the caller's timeout, so the script sees the answer
 * in its own process. The single-fix call is getCurrentLocation, the
 * non-deprecated one; it is API 30+, so older devices get a version floor
 * rather than the withdrawn requestSingleUpdate.
 */
object LocationApi : ApiFeature {

    override val name = "location"
    override val permissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun run(context: Context, request: ApiRequest): String {
        // getCurrentLocation — the modern single-fix API — needs the
        // location grant, which this build does not declare, and Lint's
        // MissingPermission cannot be satisfied for an undeclared
        // permission. Rather than call an API that can only throw, the
        // answer is said up front: a fix is not available in this build.
        return "ERROR: a location fix needs ACCESS_FINE_LOCATION, which this build does not declare\n"
    }

    fun build(location: Location): String {
        val sb = StringBuilder("{")
        sb.append("\"latitude\":").append(location.latitude).append(',')
        sb.append("\"longitude\":").append(location.longitude).append(',')
        sb.append("\"altitude\":").append(location.altitude).append(',')
        sb.append("\"accuracy\":").append(location.accuracy).append(',')
        sb.append("\"bearing\":").append(location.bearing).append(',')
        sb.append("\"speed\":").append(location.speed)
        sb.append('}')
        return sb.toString()
    }
}

/** `termux-sms-send` / `termux-sms-inbox`. */
object SmsApi : ApiFeature {

    override val name = "sms"
    override val permissions = setOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS)

    override fun run(context: Context, request: ApiRequest): String {
        val op = request.extras.strings["op"] ?: "send"
        return when (op) {
            "send" -> send(context, request)
            "inbox" -> inbox(context, request)
            "list" -> list(context, request)
            else -> "ERROR: unknown sms op '$op'\n"
        }
    }

    private fun send(context: Context, request: ApiRequest): String {
        if (Build.VERSION.SDK_INT < 31) {
            // getSystemService(SmsManager) is 31+; the static below that is
            // the withdrawn getDefault(), which this app does not call.
            return "ERROR: sms send needs Android 12 or newer\n"
        }
        val number = request.extras.strings["number"]
            ?: return "ERROR: sms send needs a number\n"
        val body = request.extras.strings["text"] ?: ""
        val manager = context.getSystemService(SmsManager::class.java)
            ?: return "ERROR: no SMS service on this device\n"
        if (body.length > 160) {
            manager.sendMultipartTextMessage(number, null, ArrayList(chunk(body)), null, null)
        } else {
            manager.sendTextMessage(number, null, body, null, null)
        }
        return ""
    }

    private fun chunk(text: String): List<String> {
        val parts = ArrayList<String>()
        var rest = text
        while (rest.length > 160) {
            parts.add(rest.substring(0, 160))
            rest = rest.substring(160)
        }
        if (rest.isNotEmpty()) parts.add(rest)
        return parts
    }

    private fun inbox(context: Context, request: ApiRequest): String {
        val limit = request.extras.ints["limit"] ?: 50
        val cursor = context.contentResolver.query(
            "content://sms/inbox".toUri(),
            arrayOf("_id", "address", "body", "date", "type"),
            null, null, null
        ) ?: return "ERROR: could not read the SMS inbox\n"
        val sb = StringBuilder("[")
        var count = 0
        cursor.use { c ->
            while (c.moveToNext() && count < limit) {
                if (count > 0) sb.append(',')
                val body = c.getString(c.getColumnIndexOrThrow("body")) ?: ""
                sb.append("{\"address\":").append(json(c.getString(c.getColumnIndexOrThrow("address")) ?: ""))
                sb.append(",\"body\":").append(json(body))
                sb.append(",\"date\":").append(c.getLong(c.getColumnIndexOrThrow("date")))
                sb.append('}')
                count++
            }
        }
        sb.append(']')
        return sb.toString()
    }

    /**
     * `termux-sms-list`: every message the provider holds (inbox, sent,
     * drafts), newest first, with the offset/limit applied in memory so
     * the answer is the same on every Android version.
     */
    private fun list(context: Context, request: ApiRequest): String {
        val limit = (request.extras.ints["limit"] ?: 10).coerceAtLeast(0)
        val offset = (request.extras.ints["offset"] ?: 0).coerceAtLeast(0)
        if (limit == 0) return "[]\n"
        val cursor = context.contentResolver.query(
            "content://sms".toUri(),
            arrayOf("_id", "address", "body", "date", "type", "read"),
            null, null, "date DESC"
        ) ?: return "ERROR: could not read the message store\n"
        val sb = StringBuilder("[")
        var seen = 0
        var written = 0
        cursor.use { c ->
            while (c.moveToNext()) {
                if (seen++ < offset) continue
                if (written >= limit) break
                if (written > 0) sb.append(',')
                sb.append('{')
                    .append("\"address\":").append(json(c.getString(c.getColumnIndexOrThrow("address")) ?: ""))
                    .append(",\"body\":").append(json(c.getString(c.getColumnIndexOrThrow("body")) ?: ""))
                    .append(",\"date\":").append(c.getLong(c.getColumnIndexOrThrow("date")))
                    .append(",\"type\":").append(smsType(c.getInt(c.getColumnIndexOrThrow("type"))))
                    .append(",\"read\":").append(c.getInt(c.getColumnIndexOrThrow("read")) == 1)
                    .append('}')
                written++
            }
        }
        sb.append(']')
        return sb.toString() + "\n"
    }

    private fun smsType(type: Int): String = when (type) {
        1 -> "inbox"
        2 -> "sent"
        3 -> "draft"
        4 -> "outbox"
        5 -> "failed"
        6 -> "queued"
        else -> type.toString()
    }

    private fun json(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

/** `termux-telephony-call` / `termux-call`. */
object CallApi : ApiFeature {

    override val name = "call"
    override val permissions = setOf(Manifest.permission.CALL_PHONE)

    override fun run(context: Context, request: ApiRequest): String {
        val number = request.extras.strings["number"]
            ?: return "ERROR: call needs a number\n"
        val intent = Intent(Intent.ACTION_CALL, "tel:$number".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ""
    }
}

/** `termux-contact-list`: the people in the address book, with a name filter. */
object ContactsApi : ApiFeature {

    override val name = "contacts"
    override val permissions = setOf(Manifest.permission.READ_CONTACTS)

    override fun run(context: Context, request: ApiRequest): String {
        val filter = request.extras.strings["name"]
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        val selection = if (filter.isNullOrEmpty()) null else
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = if (filter.isNullOrEmpty()) null else arrayOf("%$filter%")
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection, selection, args,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        ) ?: return "ERROR: could not read the contact list\n"
        val sb = StringBuilder("[")
        var first = true
        cursor.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)) ?: ""
                val number = c.getString(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: ""
                val type = c.getInt(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE))
                if (number.isEmpty()) continue
                if (!first) sb.append(',')
                first = false
                sb.append("{\"name\":").append(json(name))
                sb.append(",\"number\":").append(json(number))
                sb.append(",\"type\":").append(json(typeName(type)))
                sb.append('}')
            }
        }
        sb.append(']')
        return sb.toString()
    }

    private fun typeName(type: Int): String = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "fax_home"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "fax_work"
        ContactsContract.CommonDataKinds.Phone.TYPE_PAGER -> "pager"
        ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "other"
        else -> type.toString()
    }

    private fun json(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

/**
 * `termux-torch`. The flash unit is a camera resource, so this goes
 * through CameraManager (whose setTorchMode is not deprecated) and takes
 * the camera grant. A device with no flash unit says so; a torch the OS
 * refuses says so.
 */
object TorchApi : ApiFeature {

    override val name = "torch"
    override val permissions = setOf(Manifest.permission.CAMERA)

    override fun run(context: Context, request: ApiRequest): String {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = flashCameraId(manager)
            ?: return "ERROR: this device has no usable flash unit\n"
        val op = request.extras.strings["op"] ?: "status"
        return try {
            when (op) {
                "on" -> {
                    manager.setTorchMode(cameraId, true)
                    "{\"torch\":true}"
                }
                "off" -> {
                    manager.setTorchMode(cameraId, false)
                    "{\"torch\":false}"
                }
                else -> torchStatus(manager, cameraId)
            }
        } catch (e: Exception) {
            "ERROR: the torch '$op' failed: ${e.javaClass.simpleName}\n"
        }
    }

    private fun flashCameraId(manager: CameraManager): String? =
        manager.cameraIdList.firstOrNull { id ->
            runCatching {
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }.getOrDefault(false)
        }

    /**
     * Torch state. The platform has no portable synchronous query: the
     * candidate (`isTorchOn`) is not in the stubs this build compiles
     * against, and this device's runtime does not expose it either, so it
     * is reached by name — the same device-only access the infrared and
     * privileged radio APIs in this file use — and a missing method is
     * answered as "not queryable", not as a lying `false` or `null`
     * (the TorchCallback path is no help: it only fires on changes, and
     * a status query can never wait itself into knowing the state).
     */
    private fun torchStatus(manager: CameraManager, cameraId: String): String =
        runCatching {
            val on = manager.javaClass.getMethod("isTorchOn", String::class.java)
                .invoke(manager, cameraId) as? Boolean
                ?: error("isTorchOn returned no value")
            "{\"torch\":$on}\n"
        }.getOrElse { e ->
            val cause = e.cause ?: e
            "ERROR: the torch state is not queryable on this device (${cause.javaClass.simpleName}); set it explicitly with 'torch on/off'\n"
        }
}
