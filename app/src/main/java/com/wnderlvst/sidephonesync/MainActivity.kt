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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
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

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SyncHomeScreen(
                        modifier = Modifier.padding(innerPadding),
                        isRunning = isRunning.value,
                        serverUrl = serverUrl.value,
                        onStart = {
                            try {
                                SynciRuntime.start(applicationContext)
                                serverUrl.value = SynciRuntime.currentUrl() ?: "unavailable"
                                isRunning.value = true
                            } catch (_: Exception) {
                                isRunning.value = false
                                serverUrl.value = null
                            }
                        },
                        onStop = {
                            SynciRuntime.stop()
                            isRunning.value = false
                            serverUrl.value = null
                        }
                    )
                }
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
                Manifest.permission.READ_MEDIA_IMAGES
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
fun SyncHomeScreen(
    modifier: Modifier = Modifier,
    isRunning: Boolean,
    serverUrl: String?,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(
                    start = 22.dp,
                    end = 22.dp,
                    top = 22.dp,
                    bottom = 20.dp
                ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Synci",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )

                if (serverUrl != null) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = serverUrl,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    QrCodeView(
                        content = serverUrl,
                        modifier = Modifier.size(120.dp)
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    onClick = if (isRunning) onStop else onStart,
                    colors = if (isRunning) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    }
                ) {
                    Text(
                        text = if (isRunning) "Stop Sync" else "Start Sync"
                    )
                }
            }
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
            .background(Color.White, RoundedCornerShape(18.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Synci QR Code",
            modifier = Modifier.fillMaxSize()
        )
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