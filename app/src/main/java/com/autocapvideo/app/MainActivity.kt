package com.autocapvideo.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var textInput: EditText
    private lateinit var generateBtn: Button
    private lateinit var downloadBtn: Button
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var videoView: VideoView
    private var currentVideoPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textInput = findViewById(R.id.textInput)
        generateBtn = findViewById(R.id.generateBtn)
        downloadBtn = findViewById(R.id.downloadBtn)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        videoView = findViewById(R.id.videoView)

        downloadBtn.isEnabled = false

        generateBtn.setOnClickListener {
            val text = textInput.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Please enter some text", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startGeneration(text)
        }

        downloadBtn.setOnClickListener {
            currentVideoPath?.let { path ->
                saveToGallery(path)
            }
        }
    }

    private fun startGeneration(text: String) {
        generateBtn.isEnabled = false
        downloadBtn.isEnabled = false
        progressBar.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        statusText.text = "Starting..."

        lifecycleScope.launch {
            val generator = VideoGenerator(this@MainActivity)
            generator.generate(
                text = text,
                onProgress = { status ->
                    statusText.text = status
                },
                onComplete = { outputPath ->
                    progressBar.visibility = View.GONE
                    generateBtn.isEnabled = true
                    if (outputPath != null) {
                        currentVideoPath = outputPath
                        videoView.setVideoURI(Uri.fromFile(File(outputPath)))
                        videoView.start()
                        downloadBtn.isEnabled = true
                        statusText.text = "Video generated successfully!"
                    } else {
                        statusText.text = "Failed to generate video. Check logs."
                        Toast.makeText(this@MainActivity, "Generation failed", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }

    // ✅ CRASH-PROOF: Saves to app-specific external directory (No permission crashes on Android 10+)
    private fun saveToGallery(filePath: String) {
        try {
            val sourceFile = File(filePath)
            if (!sourceFile.exists()) {
                Toast.makeText(this, "Video file not found", Toast.LENGTH_SHORT).show()
                return
            }

            val destDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            if (destDir != null && !destDir.exists()) {
                destDir.mkdirs()
            }

            val destFile = File(destDir, "AutoCap_${System.currentTimeMillis()}.mp4")
            sourceFile.copyTo(destFile, overwrite = true)

            Toast.makeText(this, "Saved to: ${destFile.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Save failed", e)
            Toast.makeText(this, "Failed to save video", Toast.LENGTH_SHORT).show()
        }
    }
}
