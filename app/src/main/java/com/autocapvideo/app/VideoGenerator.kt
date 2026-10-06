package com.autocapvideo.app

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.zip.ZipInputStream

// Import Whisper Android API
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperParams
import dev.ffmpegkit.whisper.WhisperSamplingStrategy

class VideoGenerator(private val context: Context) {

    suspend fun generate(text: String, onProgress: (String) -> Unit, onComplete: (String?) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                onProgress("Downloading TTS models from GitHub Release...")
                val onnxFile = downloadIfNeeded(
                    "https://github.com/vishala5000/AutoCapVideo/releases/download/ljspeech/en_US-ljspeech-medium.onnx",
                    "ljspeech.onnx"
                )
                downloadIfNeeded(
                    "https://github.com/vishala5000/AutoCapVideo/releases/download/ljspeech/en_US-ljspeech-medium.onnx.json",
                    "ljspeech.json"
                )

                onProgress("Downloading Whisper Tiny model for perfect caption sync...")
                val whisperModelFile = downloadIfNeeded(
                    "https://github.com/vishala5000/AutoCapVideo/releases/download/ljspeech/ggml-tiny.en.bin",
                    "ggml-tiny.en.bin"
                )

                onProgress("Preparing TTS assets...")
                val tokensFile = File(context.filesDir, "tokens.txt")
                if (!tokensFile.exists()) {
                    context.assets.open("tokens.txt").use { it.copyTo(tokensFile.outputStream()) }
                }
                
                val espeakDir = File(context.filesDir, "espeak-ng-data")
                if (!espeakDir.exists()) {
                    espeakDir.mkdirs()
                    context.assets.open("espeak-ng-data.zip").use { input ->
                        extractZip(input, espeakDir)
                    }
                }

                onProgress("Preparing Poppins ExtraBold font...")
                val fontFile = File(context.filesDir, "font.ttf")
                if (!fontFile.exists()) {
                    try {
                        context.assets.open("font.ttf").use { it.copyTo(fontFile.outputStream()) }
                    } catch (e: Exception) {
                        Log.w("VideoGenerator", "font.ttf not found in assets, falling back to default system font.")
                    }
                }

                onProgress("Initializing Neural TTS...")
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = onnxFile.absolutePath,
                            tokens = tokensFile.absolutePath,
                            dataDir = espeakDir.absolutePath,
                            noiseScale = 0.667f,
                            noiseScaleW = 0.8f,
                            lengthScale = 1.0f
                        ),
                        numThreads = 1,
                        debug = false,
                        provider = "cpu"
                    ),
                    ruleFsts = "",
                    maxNumSentences = 1
                )
                val tts = OfflineTts(config)

                onProgress("Generating Speech Audio...")
                val audioFile = File(context.filesDir, "output.wav")
                val audio = tts.generate(text, speakerId = 0, speed = 1.0f)
                tts.save(audio, audioFile.absolutePath)

                onProgress("Generating Perfectly Synced Auto-Captions (Whisper)...")
                val srtFile = File(context.filesDir, "captions.srt")
                generatePerfectSRT(text, audioFile.absolutePath, whisperModelFile.absolutePath, srtFile.absolutePath)

                onProgress("Rendering YouTube Shorts Video (1080x1920)...")
                val outputFile = File(context.getExternalFilesDir(null), "autocap_video.mp4")
                val fontDir = context.filesDir.absolutePath
                
                val duration = audio.samples.size.toDouble() / tts.sampleRate
                val ffmpegCmd = "-y -f lavfi -i color=c=black:s=1080x1920:d=$duration " +
                        "-i '${audioFile.absolutePath}' " +
                        "-vf \"subtitles=filename='${srtFile.absolutePath}':fontsdir='$fontDir':force_style='FontSize=36,FontName=Poppins ExtraBold,PrimaryColour=&HFFFFFF&,OutlineColour=&H000000&,BorderStyle=1,MarginV=300,MarginL=200,MarginR=200,WrapStyle=0'\" " +
                        "-c:v libx264 -preset ultrafast -pix_fmt yuv420p -c:a aac -shortest '${outputFile.absolutePath}'"

                val session = FFmpegKit.execute(ffmpegCmd)
                if (ReturnCode.isSuccess(session.returnCode)) {
                    onComplete(outputFile.absolutePath)
                } else {
                    Log.e("VideoGenerator", "FFmpeg failed: ${session.allLogsAsString}")
                    onComplete(null)
                }
            } catch (e: Exception) {
                Log.e("VideoGenerator", "Error", e)
                onComplete(null)
            }
        }
    }

    private fun generatePerfectSRT(originalText: String, audioPath: String, modelPath: String, srtPath: String) {
        val whisper = Whisper()
        whisper.initContext(modelPath)
        
        val params = WhisperParams().apply {
            strategy = WhisperSamplingStrategy.WHISPER_SAMPLING_GREEDY
            printProgress = false
            wordTimestamps = true // ✅ Enables millisecond-accurate word boundaries
        }

        val result = whisper.fullTranscribe(audioPath, params)
        val sb = StringBuilder()
        var index = 1
        
        // Parse word-level segments from Whisper
        result.segments.forEach { segment ->
            segment.words?.forEach { word ->
                val wordText = word.text.trim().replace(Regex("\\s+"), "")
                if (wordText.isNotEmpty()) {
                    val start = formatTime(word.start)
                    val end = formatTime(word.end)
                    sb.append("$index\n$start --> $end\n$wordText\n\n")
                    index++
                }
            }
        }
        
        // Fallback: If Whisper word timestamps fail, split the original text evenly as a backup
        if (sb.isEmpty()) {
            Log.w("VideoGenerator", "Whisper word timestamps empty, falling back to even split.")
            val words = originalText.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            val duration = result.duration
            val durationPerWord = duration / words.size.coerceAtLeast(1)
            var currentTime = 0.0
            for ((i, word) in words.withIndex()) {
                val start = formatTime(currentTime)
                currentTime += durationPerWord
                val end = formatTime(currentTime)
                sb.append("${i + 1}\n$start --> $end\n$word\n\n")
            }
        }
        
        File(srtPath).writeText(sb.toString())
        whisper.freeContext()
    }

    private fun formatTime(seconds: Double): String {
        val hrs = (seconds / 3600).toInt()
        val mins = ((seconds % 3600) / 60).toInt()
        val secs = (seconds % 60).toInt()
        val ms = ((seconds - secs) * 1000).toInt()
        return String.format("%02d:%02d:%02d,%03d", hrs, mins, secs, ms)
    }

    private suspend fun downloadIfNeeded(url: String, filename: String): File {
        val file = File(context.filesDir, filename)
        if (!file.exists()) {
            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("Unexpected code $response")
                file.outputStream().use { fileOut ->
                    response.body?.byteStream()?.copyTo(fileOut)
                }
            }
        }
        return file
    }

    private fun extractZip(inputStream: java.io.InputStream, destDir: File) {
        ZipInputStream(inputStream).use { zis ->
            var ze = zis.nextEntry
            while (ze != null) {
                val fileName = ze.name.substringAfter("/")
                if (fileName.isNotEmpty()) {
                    val file = File(destDir, fileName)
                    if (ze.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { fos -> zis.copyTo(fos) }
                    }
                }
                ze = zis.nextEntry
            }
        }
    }
}
