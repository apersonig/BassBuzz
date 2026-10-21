package com.example.ldafscope

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build

/**
 * A plain-text dump of every sensor and every camera key the phone shows to apps. If the laser sensor is
 * exposed anywhere beyond focus distance, it shows up here; sharing the report is how to find out.
 */
object DeviceReport {

    private val capabilityNames = mapOf(
        0 to "BACKWARD_COMPATIBLE", 1 to "MANUAL_SENSOR", 2 to "MANUAL_POST_PROCESSING", 3 to "RAW",
        4 to "PRIVATE_REPROCESSING", 5 to "READ_SENSOR_SETTINGS", 6 to "BURST_CAPTURE",
        7 to "YUV_REPROCESSING", 8 to "DEPTH_OUTPUT", 9 to "CONSTRAINED_HIGH_SPEED_VIDEO",
        10 to "MOTION_TRACKING", 11 to "LOGICAL_MULTI_CAMERA", 12 to "MONOCHROME",
        13 to "SECURE_IMAGE_DATA", 14 to "SYSTEM_CAMERA", 15 to "OFFLINE_PROCESSING",
        16 to "ULTRA_HIGH_RESOLUTION_SENSOR", 17 to "REMOSAIC_REPROCESSING",
        18 to "DYNAMIC_RANGE_TEN_BIT", 19 to "STREAM_USE_CASE", 20 to "COLOR_SPACE_PROFILES",
    )

    fun build(context: Context, lastResult: TotalCaptureResult?): String {
        val sb = StringBuilder()
        sb.append("LDAF Scope device report\n")
        sb.append("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n")
        sb.append("Build: ${Build.DISPLAY}\n\n")
        sensors(context, sb)
        cameras(context, sb)
        captureResult(lastResult, sb)
        return sb.toString()
    }

    private fun sensors(context: Context, sb: StringBuilder) {
        val sm = context.getSystemService(SensorManager::class.java)
        val list = sm.getSensorList(Sensor.TYPE_ALL)
        sb.append("== SENSORS (${list.size}) ==\n")
        for (s in list.sortedBy { it.type }) {
            sb.append("- ${s.name}\n    vendor=${s.vendor} type=${s.type} (${s.stringType}) range=${s.maximumRange} res=${s.resolution}")
            sb.append(" power=${s.power}mA wakeUp=${s.isWakeUpSensor}\n")
        }
        sb.append("\n")
    }

    private fun cameras(context: Context, sb: StringBuilder) {
        val cm = context.getSystemService(CameraManager::class.java)
        val ids = try {
            cm.cameraIdList.toList()
        } catch (e: Exception) {
            sb.append("== CAMERAS ==\nCouldn't list cameras: ${e.message}\n\n")
            return
        }
        sb.append("== CAMERAS (${ids.size}) ==\n")
        var depthCamera: String? = null
        for (id in ids) {
            try {
                val ch = cm.getCameraCharacteristics(id)
                val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    else -> "external"
                }
                val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
                if (8 in caps) depthCamera = id
                sb.append("- camera $id ($facing)\n")
                sb.append("    capabilities: ${caps.joinToString { capabilityNames[it] ?: it.toString() }}\n")
                val physical = ch.physicalCameraIds
                if (physical.isNotEmpty()) sb.append("    physical cameras: ${physical.joinToString()}\n")
                sb.append("    min focus distance (diopters): ${ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)}\n")
                sb.append("    focus calibration: ${ch.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)}\n")

                // Keys that don't start with "android." are added by the phone maker.
                val vendor = ch.keys.filter { !it.name.startsWith("android.") }
                if (vendor.isNotEmpty()) {
                    sb.append("    vendor characteristics (${vendor.size}):\n")
                    for (k in vendor) {
                        @Suppress("UNCHECKED_CAST")
                        val v = try {
                            ch.get(k as CameraCharacteristics.Key<Any>)
                        } catch (e: Exception) {
                            "<error ${e.javaClass.simpleName}>"
                        }
                        sb.append("      ${k.name} = ${format(v)}\n")
                    }
                }
            } catch (e: Exception) {
                sb.append("- camera $id: couldn't read (${e.message})\n")
            }
        }
        sb.append(
            if (depthCamera != null) "\nDEPTH_OUTPUT camera found: $depthCamera (a depth stream may be readable)\n"
            else "\nNo camera advertises DEPTH_OUTPUT, so there is no standard depth stream.\n",
        )
        sb.append("\n")
    }

    private fun captureResult(result: TotalCaptureResult?, sb: StringBuilder) {
        sb.append("== LATEST CAMERA FRAME RESULT ==\n")
        if (result == null) {
            sb.append("No frame yet. Grant camera access and let the live readout run, then build the report again.\n")
            return
        }
        val keys = result.keys
        val vendor = keys.filter { !it.name.startsWith("android.") }
        sb.append("${keys.size} keys, ${vendor.size} from the phone maker\n")
        sb.append("-- vendor keys --\n")
        for (k in vendor) sb.append("  ${k.name} = ${value(result, k)}\n")
        sb.append("-- standard keys (focus related first) --\n")
        val (focus, rest) = keys.filter { it.name.startsWith("android.") }.partition {
            it.name.contains("lens") || it.name.contains("afState") || it.name.contains("afMode") || it.name.contains("focus", true)
        }
        for (k in focus + rest) sb.append("  ${k.name} = ${value(result, k)}\n")
        sb.append(
            "\nLENS_FOCUS_DISTANCE = ${result.get(CaptureResult.LENS_FOCUS_DISTANCE)} diopters\n",
        )
    }

    private fun value(result: TotalCaptureResult, key: CaptureResult.Key<*>): String = try {
        @Suppress("UNCHECKED_CAST")
        format(result.get(key as CaptureResult.Key<Any>))
    } catch (e: Exception) {
        "<error ${e.javaClass.simpleName}>"
    }

    private fun format(v: Any?): String {
        val s = when (v) {
            null -> "null"
            is IntArray -> v.joinToString(prefix = "[", postfix = "]")
            is FloatArray -> v.joinToString(prefix = "[", postfix = "]")
            is LongArray -> v.joinToString(prefix = "[", postfix = "]")
            is DoubleArray -> v.joinToString(prefix = "[", postfix = "]")
            is BooleanArray -> v.joinToString(prefix = "[", postfix = "]")
            is ByteArray -> v.take(32).joinToString(prefix = "bytes[", postfix = if (v.size > 32) ", ...]" else "]") { "%02x".format(it) }
            is Array<*> -> v.joinToString(prefix = "[", postfix = "]") { format(it) }
            else -> v.toString()
        }
        return if (s.length > 240) s.take(240) + "..." else s
    }
}
