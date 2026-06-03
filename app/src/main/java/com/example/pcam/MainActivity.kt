package com.example.pcam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Text
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show()
                recreate() // Recreate the activity to apply the permission change
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                setContent {
                    CameraScreen()
                }
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
}

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val previewView = remember { PreviewView(context) }

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var activeCamera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var isClientConnected by remember { mutableStateOf(false) }

    val activity = LocalContext.current as? android.app.Activity
    LaunchedEffect(isClientConnected) {
        activity?.window?.let { window ->
            val params = window.attributes
            if (isClientConnected) {
                params.screenBrightness = 0.01f
            } else {
                params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            window.attributes = params
        }
    }

    // H.264 encoder settings
    val ENCODER_WIDTH = 1280
    val ENCODER_HEIGHT = 720
    val ENCODER_FPS = 60
    val ENCODER_BITRATE = 8_000_000 // 8 Mbps for 720p60
    val USE_H264 = remember { mutableStateOf(true) } // Enable H.264 encoding

    var clientSocket by remember { mutableStateOf<Socket?>(null) }

    // Shared queue between accept/sender and the analyzer
    val sendQueue = remember { LinkedBlockingDeque<ByteArray>(48) }
    val senderRunning = remember { AtomicBoolean(false) }
    var senderThread by remember { mutableStateOf<Thread?>(null) }
    
    // NsdManager variables
    val nsdManager = remember { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    var registrationListener by remember { mutableStateOf<NsdManager.RegistrationListener?>(null) }

    // helper to build a single byte[] containing header (rotation,int) + size(int) + jpeg bytes
    fun makeQueuedFrame(rotation: Int, jpegData: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        header.putInt(rotation)
        header.putInt(jpegData.size)
        val out = ByteArray(header.capacity() + jpegData.size)
        System.arraycopy(header.array(), 0, out, 0, header.capacity())
        System.arraycopy(jpegData, 0, out, header.capacity(), jpegData.size)
        return out
    }

    LaunchedEffect(Unit) {
        // Register NSD service
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "PCam"
            serviceType = "_pcam._tcp."
            port = 8080
        }
        
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d("NsdManager", "Service registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("NsdManager", "Registration failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d("NsdManager", "Service unregistered")
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("NsdManager", "Unregistration failed: $errorCode")
            }
        }
        
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e("NsdManager", "Failed to register NSD service: ${e.message}")
        }
        
        withContext(Dispatchers.IO) {
            while (isActive) {
                try {
                    ServerSocket(8080).use { serverSocket ->
                        Log.d("TcpServer", "Server started, waiting for a client...")
                        val socket = serverSocket.accept()
                        clientSocket = socket
                        Log.d("TcpServer", "Client connected: ${socket.inetAddress}")
                        isClientConnected = true

                        // disable Nagle
                        try {
                            socket.tcpNoDelay = true
                        } catch (e: Exception) {
                            Log.w("TcpServer", "Could not set tcpNoDelay: ${e.message}")
                        }

                        // prepare buffered output for efficient writes
                        try {
                            socket.setSendBufferSize(256 * 1024)
                            socket.setReceiveBufferSize(256 * 1024)
                        } catch (e: Exception) {
                            // ignore if not supported
                        }
                        val bos = BufferedOutputStream(socket.getOutputStream(), 256 * 1024)

                        // Wait briefly for camera to start producing frames
                        var waitCount = 0
                        while (waitCount < 20 && sendQueue.isEmpty()) {
                            Thread.sleep(100)
                            waitCount++
                        }
                        Log.d("TcpServer", "Camera ready check: waited ${waitCount * 100}ms")

                        // Send H.264 handshake if encoder enabled
                        if (USE_H264.value) {
                            try {
                                val headerBuf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                                headerBuf.put("H264".toByteArray())
                                headerBuf.putInt(ENCODER_WIDTH)
                                headerBuf.putInt(ENCODER_HEIGHT)
                                headerBuf.putInt(ENCODER_FPS)
                                bos.write(headerBuf.array())
                                bos.flush()
                                Log.d("TcpServer", "Sent H264 handshake: ${ENCODER_WIDTH}x${ENCODER_HEIGHT}@${ENCODER_FPS}fps")
                            } catch (e: Exception) {
                                Log.w("TcpServer", "Failed to send handshake: ${e.message}")
                            }
                        }

                        // Start sender thread which consumes sendQueue for frame data
                        senderRunning.set(true)
                        senderThread = Thread {
                            try {
                                while (senderRunning.get() && socket.isConnected && !socket.isClosed) {
                                    val item = sendQueue.take() // blocking wait
                                    try {
                                        bos.write(item)
                                        // Flush immediately for low latency
                                        try {
                                            bos.flush()
                                        } catch (_: Exception) {}
                                    } catch (ioe: Exception) {
                                        Log.e("TcpServer", "Sender write failed: ${ioe.message}")
                                        break
                                    }
                                }
                            } catch (ie: InterruptedException) {
                                // Exit thread
                            } finally {
                                try { bos.flush() } catch (_: Exception) {}
                                try { bos.close() } catch (_: Exception) {}
                                try { socket.close() } catch (_: Exception) {}
                            }
                        }.also { it.isDaemon = true; it.start() }

                        // keep this coroutine alive while socket is connected
                        while (isActive && socket.isConnected && !socket.isClosed) {
                            try {
                                // simple sleep; do not call sendUrgentData (remove it)
                                Thread.sleep(1000)
                            } catch (e: InterruptedException) {
                                break
                            }
                        }

                        // stop sender thread & cleanup
                        senderRunning.set(false)
                        senderThread?.interrupt()
                        senderThread = null
                        sendQueue.clear()
                        clientSocket?.close()
                        clientSocket = null
                        isClientConnected = false
                    }
                } catch (e: Exception) {
                    Log.e("TcpServer", "Server loop error: ${e.message}")
                    // short backoff to avoid hot loop
                    try { Thread.sleep(300) } catch (_: Exception) {}
                } finally {
                    Log.d("TcpServer", "Client disconnected. Waiting for a new client.")
                    clientSocket?.close()
                    clientSocket = null
                    isClientConnected = false
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            clientSocket?.close()
            // stop sender if active
            senderRunning.set(false)
            senderThread?.interrupt()
            senderThread = null
            
            // Unregister NSD service
            registrationListener?.let { listener ->
                try {
                    nsdManager.unregisterService(listener)
                } catch (e: Exception) {
                    Log.e("NsdManager", "Failed to unregister NSD service: ${e.message}")
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            while (isActive) {
                try {
                    ServerSocket(8081).use { serverSocket ->
                        Log.d("ControlServer", "Control server started on 8081")
                        while (isActive) {
                            val socket = serverSocket.accept()
                            try {
                                val reader = socket.getInputStream().bufferedReader()
                                val cmd = reader.readLine()
                                Log.d("ControlServer", "Received command: $cmd")
                                
                                if (cmd == "CMD:FLASH_TOGGLE") {
                                    activeCamera?.let { cam ->
                                        if (cam.cameraInfo.hasFlashUnit()) {
                                            val currentTorchState = cam.cameraInfo.torchState.value ?: 0
                                            cam.cameraControl.enableTorch(currentTorchState == 0)
                                        }
                                    }
                                } else if (cmd == "CMD:CAM_SWITCH") {
                                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) 
                                        CameraSelector.LENS_FACING_FRONT 
                                    else 
                                        CameraSelector.LENS_FACING_BACK
                                }
                            } catch (e: Exception) {
                                Log.e("ControlServer", "Command error: ${e.message}")
                            } finally {
                                try { socket.close() } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ControlServer", "Control server error: ${e.message}")
                    try { Thread.sleep(1000) } catch (_: Exception) {}
                }
            }
        }
    }

    LaunchedEffect(cameraProviderFuture, lensFacing) {
        val cameraProvider = cameraProviderFuture.get()

        // Setup H.264 encoder (buffer mode - no surface)
        var mediaCodec: MediaCodec? = null
        if (USE_H264.value) {
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ENCODER_WIDTH, ENCODER_HEIGHT)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                format.setInteger(MediaFormat.KEY_BIT_RATE, ENCODER_BITRATE)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, ENCODER_FPS)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

                mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                mediaCodec.start()
                Log.d("MediaCodec", "H.264 encoder started: ${ENCODER_WIDTH}x${ENCODER_HEIGHT}@${ENCODER_FPS}fps")
            } catch (e: Exception) {
                Log.e("MediaCodec", "Failed to create encoder: ${e.message}")
                mediaCodec = null
                USE_H264.value = false
            }
        }

        // Simple preview (not shown, just for camera to work)
        val preview = Preview.Builder().build()

        // --- Camera Setup ---
        val imageAnalysisBuilder = ImageAnalysis.Builder()
            .setTargetResolution(Size(ENCODER_WIDTH, ENCODER_HEIGHT))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

        // Request target FPS
        val camera2Interop = Camera2Interop.Extender(imageAnalysisBuilder)
        camera2Interop.setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            Range(ENCODER_FPS, ENCODER_FPS)
        )

        val imageAnalyzer = imageAnalysisBuilder.build()
            .also {
                if (mediaCodec != null) {
                    // H.264 encoding path
                    val analyzer = H264EncoderAnalyzer(mediaCodec, sendQueue)
                    it.setAnalyzer(Executors.newSingleThreadExecutor(), analyzer)
                } else {
                    // JPEG fallback
                    val analyzer = JpegStreamerAnalyzer { rotationDegrees, jpegData ->
                        val queued = makeQueuedFrame(rotationDegrees, jpegData)
                        if (!sendQueue.offer(queued)) {
                            sendQueue.poll()
                            sendQueue.offer(queued)
                        }
                    }
                    it.setAnalyzer(Executors.newSingleThreadExecutor(), analyzer)
                }
            }

        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        try {
            cameraProvider.unbindAll()
            activeCamera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            Log.d("CameraXApp", "Camera binding SUCCESS")
        } catch (exc: Exception) {
            Log.e("CameraXApp", "Use case binding failed", exc)
        }
    }
    
    // Separate DisposableEffect for encoder cleanup
    DisposableEffect(USE_H264.value) {
        onDispose {
            // Cleanup will be handled when composable is disposed
        }
    }

    if (isClientConnected) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Streaming Active\nScreen dimmed to save battery",
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView({ previewView }, modifier = Modifier.fillMaxSize())
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher),
                contentDescription = "PCam Logo",
                modifier = Modifier
                    .padding(24.dp)
                    .size(56.dp)
                    .align(Alignment.TopStart)
            )
        }
    }
}

private class H264EncoderAnalyzer(
    private val encoder: MediaCodec,
    private val sendQueue: LinkedBlockingDeque<ByteArray>
) : ImageAnalysis.Analyzer {

    private var frameCount = 0
    private val startTime = System.currentTimeMillis()
    private var configData: ByteArray? = null // Cache SPS/PPS
    private var formatChecked = false // Track if we checked MediaFormat for SPS/PPS

    override fun analyze(image: ImageProxy) {
        try {
            frameCount++
            if (frameCount % 60 == 0) {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                val fps = frameCount / elapsed
                Log.d("H264Encoder", "Camera FPS: ${fps.toInt()}, frames: $frameCount")
            }

            // Get input buffer from encoder
            val inputBufferIndex = encoder.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
                if (inputBuffer != null) {
                    // Convert ImageProxy YUV to NV12 for encoder
                    val yuvBytes = imageToNV12(image)
                    inputBuffer.clear()
                    inputBuffer.put(yuvBytes)

                    encoder.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        yuvBytes.size,
                        System.nanoTime() / 1000,
                        0
                    )
                }
            }

            // Drain output buffers
            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            
            // Try to extract SPS/PPS from MediaFormat (only once)
            if (!formatChecked && configData == null) {
                formatChecked = true
                try {
                    val format = encoder.outputFormat
                    val csd0 = format.getByteBuffer("csd-0") // SPS
                    val csd1 = format.getByteBuffer("csd-1") // PPS
                    
                    if (csd0 != null && csd1 != null) {
                        val sps = ByteArray(csd0.remaining())
                        val pps = ByteArray(csd1.remaining())
                        csd0.get(sps)
                        csd1.get(pps)
                        
                        // Combine SPS and PPS with Annex-B start codes
                        val combined = ByteArrayOutputStream()
                        combined.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
                        combined.write(sps)
                        combined.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
                        combined.write(pps)
                        configData = combined.toByteArray()
                        
                        Log.d("H264Encoder", "Extracted SPS/PPS from MediaFormat: ${configData!!.size} bytes")
                        Log.d("H264Encoder", "SPS size: ${sps.size}, PPS size: ${pps.size}")
                    } else {
                        Log.w("H264Encoder", "MediaFormat has no csd-0 or csd-1")
                    }
                } catch (e: Exception) {
                    Log.e("H264Encoder", "Failed to extract from MediaFormat: ${e.message}")
                }
            }
            
            while (outputBufferIndex >= 0) {
                val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    val outData = ByteArray(bufferInfo.size)
                    outputBuffer.get(outData)

                    // Check flags
                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                    // DEBUG: Log first 20 buffers
                    if (frameCount <= 20) {
                        val hexStart = if (outData.size >= 8) {
                            outData.take(8).joinToString(" ") { b -> "%02X".format(b.toInt() and 0xFF) }
                        } else ""
                        val format = if (outData.size >= 4 && outData[0] == 0.toByte() && outData[1] == 0.toByte() && 
                            outData[2] == 0.toByte() && outData[3] == 1.toByte()) "Annex-B" else "Length-prefixed"
                        Log.d("H264Encoder", "Buffer #$frameCount: size=${outData.size}, config=$isConfig, key=$isKeyFrame, format=$format, start: $hexStart")
                    }

                    // Convert to Annex-B
                    val annexB = toAnnexB(outData, bufferInfo.flags)
                    
                    // Also detect SPS/PPS by NAL unit type (7=SPS, 8=PPS)
                    val hasSPS = annexB.size >= 5 && (annexB[4].toInt() and 0x1F) == 7
                    var hasPPS = false
                    for (idx in 0 until annexB.size - 4) {
                        if (annexB[idx] == 0.toByte() && annexB[idx+1] == 0.toByte() && 
                            annexB[idx+2] == 0.toByte() && annexB[idx+3] == 1.toByte() &&
                            idx + 4 < annexB.size && (annexB[idx+4].toInt() and 0x1F) == 8) {
                            hasPPS = true
                            break
                        }
                    }
                    
                    if (isConfig || hasSPS || hasPPS) {
                        // Cache SPS/PPS (don't send separately - will prepend to keyframes)
                        // If we already have configData and this is another config buffer, append it
                        if (configData == null) {
                            configData = annexB
                        } else {
                            // Append new config data (e.g., SPS came first, now PPS)
                            val combined = ByteArrayOutputStream()
                            combined.write(configData)
                            combined.write(annexB)
                            configData = combined.toByteArray()
                        }
                        val nalHeader = if (annexB.size >= 5) "${annexB[4].toInt() and 0x1F}" else "?"
                        val hexDump = annexB.take(annexB.size.coerceAtMost(32)).joinToString(" ") { b -> "%02X".format(b.toInt() and 0xFF) }
                        Log.d("H264Encoder", "Cached config (${if (hasSPS) "SPS" else ""}${if (hasSPS && hasPPS) "+" else ""}${if (hasPPS) "PPS" else ""}): ${annexB.size} bytes, total cached: ${configData!!.size} bytes")
                        Log.d("H264Encoder", "  NAL type: $nalHeader, flags: config=$isConfig key=$isKeyFrame, hex: $hexDump")
                    } else if (isKeyFrame) {
                        // Keyframe: prepend SPS/PPS if available, otherwise extract from frame if it's first keyframe
                        if (configData == null) {
                            // First keyframe might contain SPS/PPS already - try to extract them
                            // Look for SPS (NAL type 7) and PPS (NAL type 8) at the start
                            val spsStart = annexB.indexOfFirst { it == 0.toByte() }
                            if (spsStart >= 0 && annexB.size > spsStart + 4) {
                                val firstNalType = annexB[spsStart + 4].toInt() and 0x1F
                                if (firstNalType == 7 || firstNalType == 8) {
                                    // Frame starts with SPS or PPS, find where IDR (type 5) starts
                                    var idrStart = -1
                                    for (i in 4 until annexB.size - 4) {
                                        if (annexB[i] == 0.toByte() && annexB[i+1] == 0.toByte() && 
                                            annexB[i+2] == 0.toByte() && annexB[i+3] == 1.toByte() &&
                                            (annexB[i+4].toInt() and 0x1F) == 5) {
                                            idrStart = i
                                            break
                                        }
                                    }
                                    if (idrStart > 0) {
                                        // Extract SPS/PPS (everything before IDR)
                                        configData = annexB.copyOfRange(0, idrStart)
                                        Log.d("H264Encoder", "Extracted SPS/PPS from first keyframe: ${configData!!.size} bytes")
                                        // Send full frame as-is (includes SPS/PPS+IDR)
                                        if (!sendQueue.offer(annexB)) {
                                            sendQueue.poll()
                                            sendQueue.offer(annexB)
                                        }
                                        Log.d("H264Encoder", "Sent first keyframe with embedded SPS/PPS: ${annexB.size} bytes")
                                    } else {
                                        // No IDR found, send as-is
                                        if (!sendQueue.offer(annexB)) {
                                            sendQueue.poll()
                                            sendQueue.offer(annexB)
                                        }
                                        Log.d("H264Encoder", "Sent keyframe (no SPS/PPS extracted): ${annexB.size} bytes")
                                    }
                                } else {
                                    // First NAL is IDR, no SPS/PPS - send as-is (will likely fail decode)
                                    if (!sendQueue.offer(annexB)) {
                                        sendQueue.poll()
                                        sendQueue.offer(annexB)
                                    }
                                    Log.w("H264Encoder", "Keyframe without SPS/PPS: ${annexB.size} bytes, NAL type: $firstNalType")
                                }
                            }
                        } else {
                            // Prepend cached SPS/PPS to keyframe
                            val combined = ByteArrayOutputStream()
                            combined.write(configData)
                            combined.write(annexB)
                            val keyframeData = combined.toByteArray()
                            
                            if (!sendQueue.offer(keyframeData)) {
                                sendQueue.poll()
                                sendQueue.offer(keyframeData)
                            }
                            val nalType = if (annexB.size >= 5) "${annexB[4].toInt() and 0x1F}" else "?"
                            Log.d("H264Encoder", "Sent keyframe (NAL $nalType) with SPS/PPS: ${keyframeData.size} bytes")
                        }
                    } else {
                        // Regular frame (P-frame)
                        if (!sendQueue.offer(annexB)) {
                            sendQueue.poll()
                            sendQueue.offer(annexB)
                        }
                        // Log occasionally
                        if (frameCount % 120 == 0) {
                            val nalType = if (annexB.size >= 5) "${annexB[4].toInt() and 0x1F}" else "?"
                            Log.d("H264Encoder", "Sent P-frame (NAL $nalType): ${annexB.size} bytes")
                        }
                    }
                }
                encoder.releaseOutputBuffer(outputBufferIndex, false)
                outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.e("H264Encoder", "Error encoding frame: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun imageToNV12(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val nv12 = ByteArray(width * height * 3 / 2)

        // Copy Y plane
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        var yPos = 0
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv12, yPos, width)
            yPos += width
        }

        // Copy UV planes (interleaved for NV12)
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        var uvPos = width * height
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val uIndex = row * uRowStride + col * uPixelStride
                val vIndex = row * vRowStride + col * vPixelStride
                if (uIndex < uBuffer.capacity() && vIndex < vBuffer.capacity()) {
                    nv12[uvPos++] = uBuffer[uIndex]
                    nv12[uvPos++] = vBuffer[vIndex]
                }
            }
        }

        return nv12
    }

    private fun toAnnexB(data: ByteArray, flags: Int): ByteArray {
        // If already Annex-B, return as-is
        if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            data[2] == 0.toByte() && data[3] == 1.toByte()) {
            return data
        }

        // Convert length-prefixed to Annex-B
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val out = ByteArrayOutputStream()

        while (bb.remaining() > 4) {
            val nalLength = bb.int
            if (nalLength <= 0 || nalLength > bb.remaining()) break

            // Write start code
            out.write(byteArrayOf(0, 0, 0, 1))

            // Write NAL unit
            val nalData = ByteArray(nalLength)
            bb.get(nalData)
            out.write(nalData)
        }

        return out.toByteArray()
    }
}

private class JpegStreamerAnalyzer(
    // Updated to provide rotation and data
    private val onFrameReady: (Int, ByteArray) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private var resolutionLogged = false

        private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
            val width = image.width
            val height = image.height
            val nv21 = ByteArray(width * height * 3 / 2)

            val yPlane = image.planes[0]
            val yBuffer = yPlane.buffer
            val yRowStride = yPlane.rowStride
            var yDstIndex = 0
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, yDstIndex, width)
                yDstIndex += width
            }

            val vPlane = image.planes[2]
            val uPlane = image.planes[1]
            val vBuffer = vPlane.buffer
            val uBuffer = uPlane.buffer
            val vRowStride = vPlane.rowStride
            val uRowStride = uPlane.rowStride
            val vPixelStride = vPlane.pixelStride
            val uPixelStride = uPlane.pixelStride
            var vuDstIndex = width * height
            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    val vSrcIndex = row * vRowStride + col * vPixelStride
                    val uSrcIndex = row * uRowStride + col * uPixelStride
                    if (vSrcIndex < vBuffer.capacity() && uSrcIndex < uBuffer.capacity()) {
                        nv21[vuDstIndex++] = vBuffer[vSrcIndex]
                        nv21[vuDstIndex++] = uBuffer[uSrcIndex]
                    }
                }
            }
            return nv21
        }
    }

    override fun analyze(image: ImageProxy) {
        // Log actual resolution once
        if (!resolutionLogged) {
            Log.d("JpegAnalyzer", "Actual camera resolution: ${image.width}x${image.height}")
            resolutionLogged = true
        }

        // Get rotation before processing
        val rotationDegrees = image.imageInfo.rotationDegrees

        val nv21Data = yuv420888ToNv21(image)
        val yuvImage = YuvImage(nv21Data, ImageFormat.NV21, image.width, image.height, null)

        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 60, out) // Lower quality for smaller size
        val jpegData = out.toByteArray()

        // Pass both rotation and data
        onFrameReady(rotationDegrees, jpegData)

        image.close()
    }
}