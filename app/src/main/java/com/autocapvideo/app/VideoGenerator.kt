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

                onProgress("Generating Auto-Captions...")
                val srtFile = File(context.filesDir, "captions.srt")
                val duration = audio.samples.size.toDouble() / tts.sampleRate
                generateSRT(text, srtFile.absolutePath, duration)

                onProgress("Rendering YouTube Shorts Video (1080x1920)...")
                val outputFile = File(context.getExternalFilesDir(null), "autocap_video.mp4")
                
                // MarginL=200 + MarginR=200 = 400. 1080 - 400 = 680px text wrap width.
                // MarginV=300 ensures it sits nicely within the 1320px height area.
                val ffmpegCmd = "-y -f lavfi -i color=c=black:s=1080x1920:d=$duration " +
                        "-i '${audioFile.absolutePath}' " +
                        "-vf \"subtitles=filename='${srtFile.absolutePath}':force_style='FontSize=32,PrimaryColour=&HFFFFFF&,OutlineColour=&H000000&,BorderStyle=1,MarginV=300,MarginL=200,MarginR=200,WrapStyle=0'\" " +
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

    private fun generateSRT(text: String, srtPath: String, totalDuration: Double) {
        val words = text.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val durationPerWord = totalDuration / words.size.coerceAtLeast(1)
        val sb = StringBuilder()
        var currentTime = 0.0
        for ((index, word) in words.withIndex()) {
            val start = formatTime(currentTime)
            currentTime += durationPerWord
            val end = formatTime(currentTime)
            sb.append("${index + 1}\n$start --> $end\n$word\n\n")
        }
        File(srtPath).writeText(sb.toString())
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
                // Handle nested directories safely
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
