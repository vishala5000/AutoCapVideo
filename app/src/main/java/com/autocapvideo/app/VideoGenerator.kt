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
import java.io.File
import java.util.zip.ZipInputStream
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig

class VideoGenerator(private val context: Context) {

    suspend fun generate(text: String, onProgress: (String) -> Unit, onComplete: (String?) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                onProgress("Extracting bundled TTS model from APK...")
                val onnxFile = copyAssetIfNeeded("en_US-ljspeech-medium.onnx", "ljspeech.onnx")
                copyAssetIfNeeded("en_US-ljspeech-medium.onnx.json", "ljspeech.json")

                onProgress("Extracting bundled Whisper model from APK...")
                val whisperModelFile = copyAssetIfNeeded("ggml-tiny.en.bin", "ggml-tiny.en.bin")

                onProgress("Preparing TTS assets...")
                val tokensFile = copyAssetIfNeeded("tokens.txt", "tokens.txt")
                
                val espeakDir = File(context.filesDir, "espeak-ng-data")
                if (!espeakDir.exists()) {
                    espeakDir.mkdirs()
                    context.assets.open("espeak-ng-data.zip").use { input ->
                        extractZip(input, espeakDir)
                    }
                }

                onProgress("Preparing Poppins ExtraBold font...")
                copyAssetIfNeeded("font.ttf", "font.ttf")

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
                val tts = OfflineTts(assetManager = context.assets, config = config)

                onProgress("Generating Speech Audio...")
                val audioFile = File(context.filesDir, "output.wav")
                val audio = tts.generate(text = text, sid = 0, speed = 1.0f)
                audio.save(audioFile.absolutePath)

                onProgress("Generating Perfectly Synced Auto-Captions (Whisper)...")
                val srtFile = File(context.filesDir, "captions.srt")
                generatePerfectSRT(text, audioFile.absolutePath, whisperModelFile.absolutePath, srtFile.absolutePath)

                onProgress("Rendering YouTube Shorts Video (1080x1920)...")
                val outputFile = File(context.getExternalFilesDir(null), "autocap_video.mp4")
                val fontDir = context.filesDir.absolutePath
                
                val duration = audio.samples.size.toDouble() / tts.sampleRate()
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

    private fun copyAssetIfNeeded(assetName: String, destName: String): File {
        val destFile = File(context.filesDir, destName)
        if (!destFile.exists()) {
            context.assets.open(assetName).use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return destFile
    }

    // ✅ FIXED: Made this a suspend function and uses guaranteed segment-level timestamps
    private suspend fun generatePerfectSRT(originalText: String, audioPath: String, modelPath: String, srtPath: String) {
        val model = Whisper.loadModel(context, modelPath)
        val config = WhisperConfig(language = "en")
        val result = Whisper.transcribe(model, audioPath, config)
        
        val sb = StringBuilder()
        var index = 1
        
        // Use segment-level timestamps (guaranteed to exist in the API)
        result.segments.forEach { segment ->
            val text = segment.text.trim()
            if (text.isNotEmpty()) {
                val start = formatTimeMs(segment.startMs.toDouble())
                val end = formatTimeMs(segment.endMs.toDouble())
                sb.append("$index\n$start --> $end\n$text\n\n")
                index++
            }
        }
        
        File(srtPath).writeText(sb.toString())
        Whisper.releaseModel(model)
    }

    private fun formatTimeMs(ms: Double): String {
        val totalSeconds = ms / 1000.0
        val hrs = (totalSeconds / 3600).toInt()
        val mins = ((totalSeconds % 3600) / 60).toInt()
        val secs = (totalSeconds % 60).toInt()
        val milliseconds = (ms % 1000).toInt()
        return String.format("%02d:%02d:%02d,%03d", hrs, mins, secs, milliseconds)
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
