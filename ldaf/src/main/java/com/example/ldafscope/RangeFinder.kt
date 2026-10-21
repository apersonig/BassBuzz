package com.example.ldafscope

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.Executor
import kotlin.math.abs

/** One autofocus reading. [diopters] is where the lens is focused, in 1/metres (0 = infinity). */
class Reading(val diopters: Float, val afState: Int?)

/**
 * Android doesn't hand apps the raw laser-autofocus sensor. What the camera does report, for every
 * frame, is where its lens is focused. The phone steers the lens using the laser, so that value
 * follows the distance to whatever the camera is pointing at. This class runs the rear camera with
 * continuous autofocus (no preview needed) and passes that value on.
 */
class RangeFinder(context: Context) {

    interface Callback {
        /** Called on the camera thread for every frame. */
        fun onReading(reading: Reading)
        fun onError(message: String)
    }

    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("ldaf-camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    @Volatile
    var callback: Callback? = null

    /** The most recent full capture result, for the explorer's snapshot. */
    @Volatile
    var lastResult: TotalCaptureResult? = null
        private set

    @Volatile
    var running = false
        private set

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    fun backCameraId(): String? = manager.cameraIdList.firstOrNull {
        manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    }

    /** A short description of what the rear camera says about its own focusing. */
    fun describeFocus(): String {
        val id = backCameraId() ?: return "No rear camera found."
        val ch = manager.getCameraCharacteristics(id)
        val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        val calibration = when (ch.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)) {
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "calibrated"
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "approximate"
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED -> "uncalibrated (distances are only relative)"
            else -> "unknown"
        }
        val closest = if (minFocus != null && minFocus > 0f) " Closest focus: %.0f cm.".format(100f / minFocus) else ""
        return "Camera $id, focus distance is $calibration.$closest"
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val id = backCameraId() ?: return fail("No rear camera found")
        try {
            val ch = manager.getCameraCharacteristics(id)
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return fail("Camera has no stream configurations")
            val size = map.getOutputSizes(ImageFormat.YUV_420_888)
                ?.minByOrNull { abs(it.width - 640) + abs(it.height - 480) }
                ?: return fail("Camera can't produce a small YUV stream")

            // The frames are never looked at; the camera just needs somewhere to send them so autofocus runs.
            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            r.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
            reader = r

            running = true
            manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    createSession(camera, r, ch)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    fail("Camera was disconnected (another app may be using it)")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    fail("Camera error $error")
                }
            })
        } catch (e: Exception) {
            fail("Couldn't open the camera: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun createSession(camera: CameraDevice, r: ImageReader, ch: CameraCharacteristics) {
        try {
            val modes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            val afMode = if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes) {
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            } else {
                CaptureRequest.CONTROL_AF_MODE_AUTO
            }

            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(r.surface)),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        try {
                            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(r.surface)
                                set(CaptureRequest.CONTROL_AF_MODE, afMode)
                            }.build()
                            s.setRepeatingRequest(req, captureCallback, handler)
                        } catch (e: Exception) {
                            fail("Couldn't start capturing: ${e.message ?: e.javaClass.simpleName}")
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        fail("Couldn't set up the camera session")
                    }
                },
            )
            camera.createCaptureSession(config)
        } catch (e: Exception) {
            fail("Couldn't create the camera session: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            lastResult = result
            val d = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: return
            callback?.onReading(Reading(d, result.get(CaptureResult.CONTROL_AF_STATE)))
        }
    }

    private fun fail(message: String) {
        stop()
        callback?.onError(message)
    }

    fun stop() {
        running = false
        try {
            session?.close()
        } catch (e: Exception) {
        }
        try {
            device?.close()
        } catch (e: Exception) {
        }
        try {
            reader?.close()
        } catch (e: Exception) {
        }
        session = null
        device = null
        reader = null
    }

    fun release() {
        stop()
        thread.quitSafely()
    }
}
