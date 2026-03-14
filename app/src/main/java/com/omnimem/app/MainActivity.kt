package com.omnimem.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*

// L'import sera généré par uniffi
import uniffi.omnimem.OmniMemSession

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {

    private var omniMemSession: OmniMemSession? = null
    
    // TODO: Remplacer par votre vraie clé Groq API
    private val GROQ_API_KEY = "gsk_..."

    // Configuration Audio 16kHz PCM 16-bit Mono (Idéal pour l'ASR et Whisper)
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = 0
    private var audioRecord: AudioRecord? = null
    private var isRecordingAudio = false
    private var recordingJob: Job? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                initOmniMem()
            } else {
                Toast.makeText(this, "Permission microphone requise", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            initOmniMem()
        }

        setContent {
            OmniMemTheme {
                OmniMemApp(omniMemSession, this::startRecording, this::stopRecording)
            }
        }
    }

    private fun initOmniMem() {
        try {
            omniMemSession = OmniMemSession(GROQ_API_KEY)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Erreur Init Rust", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRecording(onTextUpdate: (String) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
        audioRecord?.startRecording()
        isRecordingAudio = true

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val audioBuffer = ByteArray(bufferSize)
            while (isRecordingAudio) {
                val readResult = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (readResult > 0) {
                    try {
                        // Envoi au Backend Rust
                        val dataToSend = audioBuffer.copyOf(readResult)
                        omniMemSession?.processAudio(dataToSend)
                        
                        // Récupération du texte partiel
                        val partial = omniMemSession?.getPartialTranscription() ?: ""
                        withContext(Dispatchers.Main) {
                            onTextUpdate(partial)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private fun stopRecording(onResult: (String) -> Unit) {
        isRecordingAudio = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        recordingJob?.cancel()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val summary = omniMemSession?.stopAndSummarize() ?: "No session"
                withContext(Dispatchers.Main) {
                    onResult(summary)
                }
            } catch(e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult("Error processing: \${e.message}")
                }
            }
        }
    }
}

@Composable
fun OmniMemTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = Colors(
            primary = Color(0xFFBB86FC),
            background = Color.Black,
            surface = Color(0xFF121212)
        ),
        content = content
    )
}

@Composable
fun OmniMemApp(session: OmniMemSession?, onStartRecording: ((String) -> Unit) -> Unit, onStopRecording: ((String) -> Unit) -> Unit) {
    var isRecording by remember { mutableStateOf(false) }
    var transcriptionText by remember { mutableStateOf("Ready to record...") }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = transcriptionText,
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.body1
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (isRecording) {
                        transcriptionText = "Processing summary..."
                        onStopRecording { result ->
                            transcriptionText = result
                        }
                    } else {
                        transcriptionText = "Listening..."
                        onStartRecording { text ->
                            transcriptionText = text
                        }
                    }
                    isRecording = !isRecording
                },
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (isRecording) Color.Red else MaterialTheme.colors.primary
                )
            ) {
                Text(if (isRecording) "Stop" else "Mic")
            }
        }
    }
}
