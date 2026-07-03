package com.wnderlvst.sidephonesync

import android.Manifest
import android.app.PendingIntent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.wnderlvst.sidephonesync.ui.theme.SidephoneSyncTheme

class MainActivity : ComponentActivity() {

    private val deleteRequestLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            // Browser can refresh after confirmation.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestMediaPermissions()
        enableEdgeToEdge()

        SynciRuntime.requestMediaDelete = { uris ->
            requestDeleteConfirmation(uris)
        }

        setContent {
            SidephoneSyncTheme {
                val isRunning = remember { mutableStateOf(SynciRuntime.isRunning()) }
                val serverUrl = remember {
                    mutableStateOf(
                        if (SynciRuntime.isRunning()) {
                            SynciRuntime.currentUrl() ?: "unavailable"
                        } else {
                            null
                        }
                    )
                }

                SynciHomeScreen(
                    isRunning = isRunning.value,
                    serverUrl = serverUrl.value,
                    onStart = {
                        try {
                            SynciRuntime.start(applicationContext)
                            serverUrl.value = SynciRuntime.currentUrl() ?: "unavailable"
                            isRunning.value = true
                        } catch (_: Exception) {
                            serverUrl.value = null
                            isRunning.value = false
                        }
                    },
                    onStop = {
                        SynciRuntime.stop(applicationContext)
                        serverUrl.value = null
                        isRunning.value = false
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        if (isFinishing) {
            SynciRuntime.requestMediaDelete = null
        }
    }

    private fun requestMediaPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        ActivityCompat.requestPermissions(this@MainActivity, permissions, 100)
    }

    private fun requestDeleteConfirmation(uris: List<Uri>) {
        if (uris.isEmpty()) return

        runOnUiThread {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val pendingIntent: PendingIntent = MediaStore.createDeleteRequest(
                    contentResolver,
                    uris
                )

                val request = IntentSenderRequest.Builder(
                    pendingIntent.intentSender
                ).build()

                deleteRequestLauncher.launch(request)
            }
        }
    }
}

@Composable
fun SynciHomeScreen(
    isRunning: Boolean,
    serverUrl: String?,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .synciDarkBackground()
            .padding(
                start = 18.dp,
                end = 18.dp,
                top = 34.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 18.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 18.dp,
                    shape = RoundedCornerShape(28.dp),
                    ambientColor = Color.Black.copy(alpha = 0.45f),
                    spotColor = Color.Black.copy(alpha = 0.65f)
                )
                .synciDarkPanel(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SynciDarkHeader()

            Column(
                modifier = Modifier.padding(
                    start = 18.dp,
                    end = 18.dp,
                    top = 14.dp,
                    bottom = 16.dp
                ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isRunning) "Server Running" else "Ready",
                    color = Color(0xFFE8E8EA),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                if (serverUrl != null) {
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = serverUrl,
                        color = Color(0xFFBFC4CC),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .synciDarkInsetField()
                            .padding(horizontal = 10.dp, vertical = 9.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    QrCodeView(
                        content = serverUrl,
                        modifier = Modifier.size(134.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                SynciDarkButton(
                    text = if (isRunning) "Stop Sync" else "Start Sync",
                    isDanger = isRunning,
                    onClick = if (isRunning) onStop else onStart
                )
            }
        }
    }
}

@Composable
fun SynciDarkHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF5A6878),
                        Color(0xFF394655),
                        Color(0xFF252C35)
                    )
                ),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            )
            .border(
                width = 1.dp,
                color = Color(0xFF11161C),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            )
            .drawBehind {
                drawLine(
                    color = Color.White.copy(alpha = 0.22f),
                    start = Offset(0f, 1f),
                    end = Offset(size.width, 1f),
                    strokeWidth = 1.5f
                )
                drawLine(
                    color = Color.Black.copy(alpha = 0.65f),
                    start = Offset(0f, size.height - 1f),
                    end = Offset(size.width, size.height - 1f),
                    strokeWidth = 1.5f
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Synci",
            color = Color(0xFFF4F5F7),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun SynciDarkButton(
    text: String,
    isDanger: Boolean,
    onClick: () -> Unit
) {
    val colors = if (isDanger) {
        listOf(
            Color(0xFFEA8D84),
            Color(0xFFB83B35),
            Color(0xFF7F1F1B)
        )
    } else {
        listOf(
            Color(0xFFA6C5E8),
            Color(0xFF5B86B8),
            Color(0xFF294B78)
        )
    }

    val borderColor = if (isDanger) {
        Color(0xFF661915)
    } else {
        Color(0xFF1E3558)
    }

    Button(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        onClick = onClick,
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White
        ),
        contentPadding = PaddingValues()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(colors),
                    shape = RoundedCornerShape(15.dp)
                )
                .border(
                    width = 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(15.dp)
                )
                .drawBehind {
                    drawLine(
                        color = Color.White.copy(alpha = 0.46f),
                        start = Offset(6f, 2f),
                        end = Offset(size.width - 6f, 2f),
                        strokeWidth = 1.6f
                    )
                    drawLine(
                        color = Color.Black.copy(alpha = 0.42f),
                        start = Offset(6f, size.height - 2f),
                        end = Offset(size.width - 6f, size.height - 2f),
                        strokeWidth = 1.6f
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
fun QrCodeView(
    content: String,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(content) {
        generateQrCodeBitmap(content, 768)
    }

    Box(
        modifier = modifier
            .background(
                brush = Brush.verticalGradient(
                    listOf(
                        Color(0xFFF7F7F7),
                        Color(0xFFE2E2E2)
                    )
                ),
                shape = RoundedCornerShape(17.dp)
            )
            .border(
                width = 1.dp,
                color = Color(0xFF9C9C9C),
                shape = RoundedCornerShape(17.dp)
            )
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Synci QR Code",
            modifier = Modifier.fillMaxSize()
        )
    }
}

fun Modifier.synciDarkPanel(): Modifier {
    return this
        .background(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF2B2D31),
                    Color(0xFF202225),
                    Color(0xFF17191C)
                )
            ),
            shape = RoundedCornerShape(28.dp)
        )
        .border(
            width = 1.dp,
            color = Color(0xFF464A50),
            shape = RoundedCornerShape(28.dp)
        )
        .drawBehind {
            drawLine(
                color = Color.White.copy(alpha = 0.18f),
                start = Offset(20f, 2f),
                end = Offset(size.width - 20f, 2f),
                strokeWidth = 1.6f
            )
        }
}

fun Modifier.synciDarkInsetField(): Modifier {
    return this
        .background(
            brush = Brush.verticalGradient(
                listOf(
                    Color(0xFF121316),
                    Color(0xFF24272C)
                )
            ),
            shape = RoundedCornerShape(11.dp)
        )
        .border(
            width = 1.dp,
            color = Color(0xFF4E535A),
            shape = RoundedCornerShape(11.dp)
        )
        .drawBehind {
            drawLine(
                color = Color.Black.copy(alpha = 0.75f),
                start = Offset(8f, 1f),
                end = Offset(size.width - 8f, 1f),
                strokeWidth = 1.4f
            )
            drawLine(
                color = Color.White.copy(alpha = 0.08f),
                start = Offset(8f, size.height - 1f),
                end = Offset(size.width - 8f, size.height - 1f),
                strokeWidth = 1.4f
            )
        }
}

fun Modifier.synciDarkBackground(): Modifier {
    return this
        .background(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF111418),
                    Color(0xFF1E242B),
                    Color(0xFF0A0B0D)
                ),
                tileMode = TileMode.Clamp
            )
        )
        .drawBehind {
            val stripeColor = Color.White.copy(alpha = 0.018f)
            val darkStripe = Color.Black.copy(alpha = 0.055f)

            var x = 0f
            while (x < size.width) {
                drawLine(
                    color = stripeColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f
                )
                drawLine(
                    color = darkStripe,
                    start = Offset(x + 2f, 0f),
                    end = Offset(x + 2f, size.height),
                    strokeWidth = 1f
                )
                x += 4f
            }
        }
}

fun generateQrCodeBitmap(content: String, size: Int): Bitmap {
    val bitMatrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        size,
        size
    )

    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    for (x in 0 until size) {
        for (y in 0 until size) {
            val color = if (bitMatrix.get(x, y)) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }

            bitmap.setPixel(x, y, color)
        }
    }

    return bitmap
}