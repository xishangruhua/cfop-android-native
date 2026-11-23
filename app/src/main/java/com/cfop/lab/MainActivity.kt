package com.cfop.lab

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.cfop.lab.solver.CubeSolverBridge
import com.cfop.lab.vision.CubeSquareDetector
import com.cfop.lab.vision.RubiksColorResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 初始化 OpenCV 原生动态库
        OpenCVLoader.initLocal()

        setContent {
            MaterialTheme {
                CFOPLabRoot(context = this)
            }
        }
    }
}

private val FACE_ORDER = listOf(
    "F" to "F 绿前",
    "R" to "R 红右",
    "B" to "B 蓝后",
    "L" to "L 橙左",
    "U" to "U 黄顶",
    "D" to "D 白底"
)
// 映射到 Kociemba 54 字符数组起始偏移：U(0), R(9), F(18), D(27), L(36), B(45)
private val FACE_OFFSET = mapOf("U" to 0, "R" to 9, "F" to 18, "D" to 27, "L" to 36, "B" to 45)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CFOPLabRoot(context: Context) {
    var activeTab by remember { mutableIntStateOf(0) } // 0 = 3D 魔方实验室, 1 = 原生 CameraX+OpenCV 扫码
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun triggerHaptic(ms: Long = 10L) {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vib.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFE8ECF2))) {
        // 3D 磁力速拧魔方主舞台（扫码时隐藏释放 GPU，避免视频合成卡顿）
        if (activeTab == 0) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun hapticTick() { triggerHaptic(8L) }
                            @JavascriptInterface
                            fun openNativeScanner() {
                                post { activeTab = 1 }
                            }
                        }, "AndroidNativeBridge")
                        loadUrl("file:///android_asset/cube_3d.html")
                        webViewRef = this
                    }
                }
            )
        } else {
            NativeCameraScannerScreen(
                onClose = { activeTab = 0 },
                onSolved = { moves ->
                    triggerHaptic(20L)
                    activeTab = 0
                    webViewRef?.post {
                        val safe = moves.replace("'", "\\'")
                        webViewRef?.evaluateJavascript(
                            "window.loadNativeSolution && window.loadNativeSolution('$safe', '原生 OpenCV+min2phase 最优解');",
                            null
                        )
                    }
                }
            )
        }

        // 顶部单色液态玻璃切换条
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xD9F4F5F8))
                .border(1.dp, Color(0x3318181B), RoundedCornerShape(999.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("3D 实验室", "OpenCV 拍照求解").forEachIndexed { idx, label ->
                val sel = activeTab == idx
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (sel) Color(0xFF18181B) else Color.Transparent)
                        .clickable { activeTab = idx }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (sel) Color.White else Color(0xFF52525B)
                    )
                }
            }
        }
    }
}

@Composable
fun NativeCameraScannerScreen(
    onClose: () -> Unit,
    onSolved: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCameraPerm = it
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPerm) permLauncher.launch(Manifest.permission.CAMERA)
    }

    var targetIdx by remember { mutableIntStateOf(0) }
    val capturedFaces = remember { mutableStateMapOf<String, List<IntArray>>() }
    var statusMsg by remember { mutableStateOf("请将 F 绿心面对准方框，点击「拍此面」进行 OpenCV 寻方裁剪") }
    var isBusy by remember { mutableStateOf(false) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(top = 56.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // CameraX 原生预览框（纯硬件直出，不实时抽帧，绝不卡顿）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF14161B)),
            contentAlignment = Alignment.Center
        ) {
            if (hasCameraPerm) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val future = ProcessCameraProvider.getInstance(ctx)
                        future.addListener({
                            val provider = future.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture
                            )
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    }
                )
            } else {
                Text("请授予相机权限以使用 OpenCV 寻方拍照", color = Color.White, fontSize = 13.sp)
            }

            // 居中参考方框
            Box(
                modifier = Modifier
                    .fillMaxSize(0.64f)
                    .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
            )
        }

        // 6 面进度芯片
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FACE_ORDER.forEachIndexed { idx, (key, _) ->
                val done = capturedFaces.containsKey(key)
                val isCur = idx == targetIdx
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            when {
                                isCur -> Color(0xFF18181B)
                                done -> Color(0x2818181B)
                                else -> Color.White
                            }
                        )
                        .clickable { targetIdx = idx }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (done) "$key✓" else key,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isCur) Color.White else Color(0xFF18181B)
                    )
                }
            }
        }

        Text(text = statusMsg, fontSize = 12.5.sp, color = Color(0xFF3F3F46))

        // 单帧抓拍 + 求解按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    if (isBusy || !hasCameraPerm) return@Button
                    isBusy = true
                    val (curKey, curName) = FACE_ORDER[targetIdx]
                    imageCapture.takePicture(
                        cameraExecutor,
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                scope.launch(Dispatchers.Default) {
                                    val bmp = imageProxyToBitmap(image)
                                    image.close() // 立即归还相机缓冲区
                                    val sample = CubeSquareDetector.detectAndSampleFace(bmp)
                                    bmp.recycle() // 识别完立刻释放 Bitmap 内存
                                    withContext(Dispatchers.Main) {
                                        capturedFaces[curKey] = sample.rgbGrid
                                        val next = FACE_ORDER.indexOfFirst { !capturedFaces.containsKey(it.first) }
                                        if (next != -1) targetIdx = next
                                        statusMsg = "已采集 $curName（${if (sample.detectedSquare) "OpenCV 已寻方裁剪" else "中心裁剪"}，进度 ${capturedFaces.size}/6）"
                                        isBusy = false
                                    }
                                }
                            }

                            override fun onError(exc: ImageCaptureException) {
                                scope.launch(Dispatchers.Main) {
                                    statusMsg = "拍照失败：${exc.message}"
                                    isBusy = false
                                }
                            }
                        }
                    )
                },
                modifier = Modifier.weight(1f).height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF18181B)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (isBusy) "识别中..." else "拍此面并裁剪", color = Color.White)
            }

            OutlinedButton(
                onClick = {
                    if (capturedFaces.size < 6) {
                        Toast.makeText(context, "请先拍完 6 个面（当前 ${capturedFaces.size}/6）", Toast.LENGTH_SHORT).show()
                        return@OutlinedButton
                    }
                    scope.launch(Dispatchers.Default) {
                        val rgb54 = MutableList(54) { intArrayOf(0, 0, 0) }
                        for ((key, grid) in capturedFaces) {
                            val off = FACE_OFFSET[key] ?: continue
                            for (i in 0 until 9) rgb54[off + i] = grid[i]
                        }
                        val stateStr = RubiksColorResolver.resolve54Facelets(rgb54)
                        val res = CubeSolverBridge.solve(stateStr)
                        withContext(Dispatchers.Main) {
                            if (res.ok) onSolved(res.moves)
                            else statusMsg = res.error
                        }
                    }
                },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("min2phase 求解", color = Color(0xFF18181B))
            }
        }
    }
}

private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val rot = image.imageInfo.rotationDegrees
    if (rot == 0) return raw
    val m = Matrix().apply { postRotate(rot.toFloat()) }
    val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    raw.recycle()
    return rotated
}
