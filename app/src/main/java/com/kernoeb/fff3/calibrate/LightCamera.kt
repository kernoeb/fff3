package com.kernoeb.fff3.calibrate

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The back camera, set up to time a light change: highest frame rate, short exposure, fixed focus and
 * white balance. For each frame it reports the mean colour of a box, timed on the
 * `SystemClock.elapsedRealtimeNanos` clock, and every few frames a small preview to aim with.
 *
 * Pixels are read through a SurfaceTexture and OpenGL because high-speed sessions only accept
 * preview and video surfaces, not an ImageReader. Some phones still send a high-speed preview at
 * about 60 frames a second; that is still more than a regular session. Open and close it on the main thread.
 */
@SuppressLint("MissingPermission") // The screen opens the camera only once CAMERA is granted.
class LightCamera(context: Context) {

    sealed interface State {
        data object Closed : State
        data object Opening : State
        /** [fps] is the frame rate seen in the last second. */
        data class Ready(val fps: Int, val highSpeed: Boolean) : State
        data class Failed(val reason: Failure) : State
    }

    enum class Failure {
        NoCamera,
        /** The sensor times are not on the elapsed realtime clock, so they cannot be compared with the writes. */
        UnknownClock,
        Unavailable,
    }

    enum class Mode {
        /** Normal auto exposure, to find the strip. */
        Aim,
        /** Short exposure, white balance free to settle on the strip. */
        Settle,
        /** Exposure, white balance and focus all held. */
        Measure,
    }

    /** The target box centre, 0..1 in the upright preview. */
    data class Box(val x: Float, val y: Float) {
        companion object {
            const val WIDTH = 0.5f
            const val HEIGHT = 0.16f
        }
    }

    private val manager = context.applicationContext.getSystemService(CameraManager::class.java)

    private val _state = MutableStateFlow<State>(State.Closed)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _preview = MutableStateFlow<Bitmap?>(null)
    /** Upright preview, refreshed about 15 times a second. */
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    private val _box = MutableStateFlow(Box(0.5f, 0.5f))
    val box: StateFlow<Box> = _box.asStateFlow()

    /** Called on the camera thread for each frame while the camera is ready. */
    @Volatile var onSample: ((LightDelayMath.Frame) -> Unit)? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // Camera thread only.
    private var setup: Setup? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: GlReader? = null
    private var mode = Mode.Aim
    private var useHighSpeed = true
    private var exposure = 0L
    private var skew = 0L
    private var frames = 0
    private var countedSince = 0L

    fun moveBox(x: Float, y: Float) {
        val halfWidth = Box.WIDTH / 2
        val halfHeight = Box.HEIGHT / 2
        _box.value = Box(x.coerceIn(halfWidth, 1 - halfWidth), y.coerceIn(halfHeight, 1 - halfHeight))
    }

    fun open() {
        if (thread != null) return
        _state.value = State.Opening
        val started = HandlerThread("fff3-camera").apply { start() }
        thread = started
        handler = Handler(started.looper).also { it.post(::openOnThread) }
    }

    /** Closes the camera and waits for its thread, so no sample arrives after this returns. */
    fun close() {
        val current = thread ?: return
        handler?.post(::closeOnThread)
        current.quitSafely()
        current.join(CLOSE_WAIT_MS)
        thread = null
        handler = null
        _state.value = State.Closed
        _preview.value = null
    }

    fun setMode(value: Mode) {
        handler?.post {
            mode = value
            startRepeating()
        }
    }

    private val executor = Executor { command -> handler?.post(command) }

    private fun openOnThread() {
        val chosen = try {
            choose()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read the camera", e)
            fail(Failure.Unavailable)
            return
        }
        if (chosen == null) {
            fail(Failure.NoCamera)
            return
        }
        if (chosen.clock != CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
            fail(Failure.UnknownClock)
            return
        }
        setup = chosen
        try {
            manager.openCamera(chosen.id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (thread == null) return camera.close()
                    device = camera
                    createSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) fail(Failure.Unavailable)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (device === camera || device == null) fail(Failure.Unavailable)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open the camera", e)
            fail(Failure.Unavailable)
        }
    }

    /** Picks the first back camera, its fastest small stream and the settings to use. */
    private fun choose(): Setup? {
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            if (c[CameraCharacteristics.LENS_FACING] != CameraCharacteristics.LENS_FACING_BACK) continue
            val map = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] ?: continue
            val caps = c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.toSet().orEmpty()

            val highSpeed = if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO in caps) {
                map.highSpeedVideoSizes.flatMap { size ->
                    map.getHighSpeedVideoFpsRangesFor(size)
                        .filter { it.lower == it.upper && it.upper <= MAX_FPS }
                        .map { size to it.upper }
                }.maxWithOrNull(compareBy<Pair<Size, Int>> { it.second }.thenBy { -it.first.width * it.first.height })
            } else {
                null
            }

            val sizes = map.getOutputSizes(SurfaceTexture::class.java)
                .filter { it.width in MIN_WIDTH..MAX_WIDTH && it.height >= MIN_HEIGHT }
            val regular = sizes.minWithOrNull(
                compareBy<Size> { map.getOutputMinFrameDuration(SurfaceTexture::class.java, it) }
                    .thenBy { abs(it.width * it.height - TARGET_AREA) },
            ) ?: continue
            val regularFps = c[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]
                ?.maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })

            val minFocus = c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f
            val hyperfocal = c[CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE] ?: 0f
            val active = c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
            return Setup(
                id = id,
                clock = c[CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE],
                orientation = c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90,
                highSpeedSize = highSpeed?.first,
                highSpeedFps = highSpeed?.second ?: 0,
                regularSize = regular,
                regularFps = regularFps,
                regularFrameNanos = map.getOutputMinFrameDuration(SurfaceTexture::class.java, regular),
                manual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
                exposureRange = c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE],
                isoRange = c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE],
                compensationMin = c[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]?.lower ?: 0,
                focus = if (minFocus > 0f) (if (hyperfocal > 0f) hyperfocal else DEFAULT_FOCUS_DIOPTERS).coerceAtMost(minFocus) else null,
                daylight = CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT in
                    (c[CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES]?.toSet().orEmpty()),
                sensorWidth = active?.width() ?: 4,
                sensorHeight = active?.height() ?: 3,
            )
        }
        return null
    }

    private fun createSession() {
        val camera = device ?: return
        val s = setup ?: return
        val highSpeed = useHighSpeed && s.highSpeedSize != null
        val size = if (highSpeed) s.highSpeedSize else s.regularSize
        val gl = reader ?: try {
            GlReader(handler!!, ::onFrame).also { reader = it }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot set up OpenGL", e)
            return fail(Failure.Unavailable)
        }
        gl.setSize(size.width, size.height)
        val output = OutputConfiguration(gl.surface).apply {
            // The buffer time must be the sensor time, which we checked is on the elapsed realtime clock.
            setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_SENSOR)
        }
        val type = if (highSpeed) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR
        val config = SessionConfiguration(type, listOf(output), executor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(created: CameraCaptureSession) {
                if (device !== camera) return created.close()
                session = created
                Log.i(TAG, "Session ${size.width}x${size.height}, high speed $highSpeed")
                if (!startRepeating() && highSpeed) retryRegular(created)
            }

            override fun onConfigureFailed(failed: CameraCaptureSession) {
                if (highSpeed) retryRegular(failed) else fail(Failure.Unavailable)
            }
        })
        try {
            camera.createCaptureSession(config)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot create the session", e)
            if (highSpeed) retryRegular(null) else fail(Failure.Unavailable)
        }
    }

    private fun retryRegular(failed: CameraCaptureSession?) {
        Log.i(TAG, "High speed session refused, using a regular one")
        failed?.close()
        session = null
        useHighSpeed = false
        createSession()
    }

    /** Sends the repeating request for [mode]. Returns false if the session refused it. */
    private fun startRepeating(): Boolean {
        val current = session ?: return false
        val camera = device ?: return false
        val s = setup ?: return false
        val surface = reader?.surface ?: return false
        val highSpeed = current is CameraConstrainedHighSpeedCaptureSession
        return try {
            val builder = camera.createCaptureRequest(if (highSpeed) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            if (highSpeed) {
                builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(s.highSpeedFps, s.highSpeedFps))
            } else {
                s.regularFps?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            }
            s.focus?.let {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, it)
            }
            builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF)
            if (mode != Mode.Aim) {
                if (s.daylight) builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT)
                if (mode == Mode.Measure) builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                if (s.manual && !highSpeed) {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, s.exposureRange?.clamp(EXPOSURE_NS) ?: EXPOSURE_NS)
                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, s.isoRange?.clamp(ISO) ?: ISO)
                    builder.set(CaptureRequest.SENSOR_FRAME_DURATION, s.regularFrameNanos)
                } else {
                    // No manual exposure here: the darkest auto exposure is the shortest one.
                    builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, s.compensationMin)
                    if (mode == Mode.Measure) builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                }
            }
            val request = builder.build()
            if (current is CameraConstrainedHighSpeedCaptureSession) {
                current.setRepeatingBurst(current.createHighSpeedRequestList(request), results, handler)
            } else {
                current.setRepeatingRequest(request, results, handler)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Repeating request refused", e)
            false
        }
    }

    private val results = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            result[CaptureResult.SENSOR_EXPOSURE_TIME]?.let { exposure = it }
            result[CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW]?.let { skew = it }
        }
    }

    private fun onFrame(timestamp: Long, read: GlReader.Read) {
        val s = setup ?: return
        val highSpeed = session is CameraConstrainedHighSpeedCaptureSession
        val now = SystemClock.elapsedRealtimeNanos()
        frames++
        if (now - countedSince >= 1_000_000_000L) {
            if (countedSince != 0L) _state.value = State.Ready(((frames * 1e9) / (now - countedSince)).roundToInt(), highSpeed)
            countedSince = now
            frames = 0
        } else if (_state.value !is State.Ready) {
            _state.value = State.Ready(0, highSpeed)
        }
        // Without a capture result yet, the exposure and readout times are unknown.
        if (exposure == 0L) return
        val row = LightDelayMath.sensorRow(read.bufferRow, read.bufferWidth, read.bufferHeight, s.sensorWidth, s.sensorHeight)
        onSample?.invoke(LightDelayMath.Frame(LightDelayMath.frameTime(timestamp, exposure, skew, row), read.u, read.v))
        read.preview?.let { _preview.value = it }
    }

    private fun closeOnThread() {
        try {
            session?.close()
            device?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot close the camera", e)
        }
        session = null
        device = null
        reader?.release()
        reader = null
    }

    private fun fail(reason: Failure) {
        closeOnThread()
        _state.value = State.Failed(reason)
    }

    private class Setup(
        val id: String,
        val clock: Int?,
        val orientation: Int,
        val highSpeedSize: Size?,
        val highSpeedFps: Int,
        val regularSize: Size,
        val regularFps: Range<Int>?,
        val regularFrameNanos: Long,
        val manual: Boolean,
        val exposureRange: Range<Long>?,
        val isoRange: Range<Int>?,
        val compensationMin: Int,
        val focus: Float?,
        val daylight: Boolean,
        val sensorWidth: Int,
        val sensorHeight: Int,
    )

    /**
     * Draws each camera frame with OpenGL: the box into a tiny viewport to average it, and now and
     * then the whole frame for the preview. Runs on the camera thread.
     */
    private inner class GlReader(handler: Handler, private val onRead: (Long, Read) -> Unit) {

        /** [bufferRow] is the box centre row, 0..1 from the top of the camera buffer. */
        inner class Read(
            val u: Double,
            val v: Double,
            val bufferRow: Double,
            val bufferWidth: Int,
            val bufferHeight: Int,
            val preview: Bitmap?,
        )

        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val eglContext: EGLContext
        private val eglSurface: EGLSurface
        private val texture: Int
        private val program: Int
        private val surfaceTexture: SurfaceTexture
        val surface: Surface
        private val matrix = FloatArray(16)
        private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0) }
        private val boxPixels = ByteBuffer.allocateDirect(BOX_W * BOX_H * 4).order(ByteOrder.nativeOrder())
        private val previewPixels = ByteBuffer.allocateDirect(PBUFFER * PBUFFER * 4).order(ByteOrder.nativeOrder())
        private var width = 0
        private var height = 0
        private var lastPreview = 0L

        init {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1))
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE,
                ),
                0, configs, 0, 1, count, 0,
            )
            val config = checkNotNull(configs[0]) { "No EGL config" }
            eglContext = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            eglSurface = EGL14.eglCreatePbufferSurface(
                display, config, intArrayOf(EGL14.EGL_WIDTH, PBUFFER, EGL14.EGL_HEIGHT, PBUFFER, EGL14.EGL_NONE), 0,
            )
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) { "Cannot use the EGL context" }

            val names = IntArray(1)
            GLES20.glGenTextures(1, names, 0)
            texture = names[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            program = buildProgram()

            surfaceTexture = SurfaceTexture(texture)
            surfaceTexture.setOnFrameAvailableListener({ onFrameAvailable() }, handler)
            surface = Surface(surfaceTexture)
        }

        fun setSize(w: Int, h: Int) {
            width = w
            height = h
            surfaceTexture.setDefaultBufferSize(w, h)
        }

        private fun onFrameAvailable() {
            try {
                surfaceTexture.updateTexImage()
            } catch (e: Exception) {
                return
            }
            val timestamp = surfaceTexture.timestamp
            surfaceTexture.getTransformMatrix(matrix)
            val s = setup ?: return
            // If the matrix swaps the axes, the camera service already turned the image upright.
            val rotation = if (abs(matrix[0]) < abs(matrix[4])) 0 else s.orientation
            val box = _box.value

            val (x0, y0) = LightDelayMath.previewToImage((box.x - Box.WIDTH / 2).toDouble(), (box.y - Box.HEIGHT / 2).toDouble(), rotation)
            val (x1, y1) = LightDelayMath.previewToImage((box.x + Box.WIDTH / 2).toDouble(), (box.y + Box.HEIGHT / 2).toDouble(), rotation)
            val left = minOf(x0, x1).toFloat()
            val right = maxOf(x0, x1).toFloat()
            val top = minOf(y0, y1).toFloat()
            val bottom = maxOf(y0, y1).toFloat()
            // Texture coordinates have y up; the matrix then maps them to the buffer, row 0 at t = 0.
            draw(left, 1 - bottom, right, 1 - top, BOX_W, BOX_H)
            boxPixels.position(0)
            GLES20.glReadPixels(0, 0, BOX_W, BOX_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, boxPixels)
            var r = 0L
            var g = 0L
            var b = 0L
            for (i in 0 until BOX_W * BOX_H) {
                r += boxPixels.get(i * 4).toInt() and 0xFF
                g += boxPixels.get(i * 4 + 1).toInt() and 0xFF
                b += boxPixels.get(i * 4 + 2).toInt() and 0xFF
            }
            val n = (BOX_W * BOX_H).toDouble()
            val luma = (0.299 * r + 0.587 * g + 0.114 * b) / n
            val centreT = 1 - (top + bottom) / 2
            val centreS = (left + right) / 2
            val bufferRow = (matrix[1] * centreS + matrix[5] * centreT + matrix[13]).toDouble().coerceIn(0.0, 1.0)

            val now = SystemClock.elapsedRealtime()
            val preview = if (now - lastPreview >= PREVIEW_EVERY_MS) {
                lastPreview = now
                readPreview(rotation)
            } else {
                null
            }
            onRead(timestamp, Read(b / n - luma, r / n - luma, bufferRow, width, height, preview))
        }

        /** Draws the texture part from (s0, t0) to (s1, t1) into a [w] × [h] viewport. */
        private fun draw(s0: Float, t0: Float, s1: Float, t1: Float, w: Int, h: Int) {
            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, matrix, 0)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uRect"), s0, t0, s1, t1)
            val position = GLES20.glGetAttribLocation(program, "aPos")
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun readPreview(rotation: Int): Bitmap {
            // The image as the matrix gives it, then turned upright.
            val swapped = abs(matrix[0]) < abs(matrix[4])
            val imageWidth = if (swapped) height else width
            val imageHeight = if (swapped) width else height
            val scale = PREVIEW_MAX.toFloat() / max(imageWidth, imageHeight)
            val w = (imageWidth * scale).roundToInt().coerceAtLeast(1)
            val h = (imageHeight * scale).roundToInt().coerceAtLeast(1)
            draw(0f, 0f, 1f, 1f, w, h)
            previewPixels.position(0)
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, previewPixels)
            val colors = IntArray(w * h)
            for (y in 0 until h) {
                // OpenGL rows go up from the bottom.
                val from = (h - 1 - y) * w * 4
                for (x in 0 until w) {
                    val i = from + x * 4
                    colors[y * w + x] = (0xFF shl 24) or
                        ((previewPixels.get(i).toInt() and 0xFF) shl 16) or
                        ((previewPixels.get(i + 1).toInt() and 0xFF) shl 8) or
                        (previewPixels.get(i + 2).toInt() and 0xFF)
                }
            }
            val bitmap = Bitmap.createBitmap(colors, w, h, Bitmap.Config.ARGB_8888)
            if (rotation == 0) return bitmap
            val turn = Matrix().apply { postRotate(rotation.toFloat()) }
            return Bitmap.createBitmap(bitmap, 0, 0, w, h, turn, false).also { bitmap.recycle() }
        }

        private fun buildProgram(): Int {
            fun shader(type: Int, source: String): Int = GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, source)
                GLES20.glCompileShader(it)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { GLES20.glGetShaderInfoLog(it) }
            }
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
            GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
            GLES20.glLinkProgram(program)
            return program
        }

        fun release() {
            surfaceTexture.setOnFrameAvailableListener(null)
            surface.release()
            surfaceTexture.release()
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglReleaseThread()
        }
    }

    private companion object {
        const val TAG = "fff3-calibrate"
        const val CLOSE_WAIT_MS = 2_000L
        const val MAX_FPS = 240
        const val MIN_WIDTH = 320
        const val MAX_WIDTH = 1920
        const val MIN_HEIGHT = 240
        const val TARGET_AREA = 640 * 480
        const val EXPOSURE_NS = 1_500_000L
        const val ISO = 400
        const val DEFAULT_FOCUS_DIOPTERS = 0.5f
        const val BOX_W = 64
        const val BOX_H = 32
        const val PBUFFER = 256
        const val PREVIEW_MAX = 240
        const val PREVIEW_EVERY_MS = 66L

        const val VERTEX_SHADER = """
            attribute vec2 aPos;
            uniform mat4 uTex;
            uniform vec4 uRect;
            varying vec2 vTex;
            void main() {
                vec2 st = mix(uRect.xy, uRect.zw, aPos * 0.5 + 0.5);
                vTex = (uTex * vec4(st, 0.0, 1.0)).xy;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTex;
            void main() {
                gl_FragColor = texture2D(uTexture, vTex);
            }
        """
    }
}
