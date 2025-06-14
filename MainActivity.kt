package com.dg.detectcash

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View // <-- Yeh import add karna padega
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    private val REQUEST_PERMISSIONS = 100
    private lateinit var cameraBtn: Button
    private lateinit var uploadBtn: Button
    private lateinit var imageView: ImageView

    private lateinit var cameraLauncher: ActivityResultLauncher<Intent>
    private lateinit var galleryLauncher: ActivityResultLauncher<Intent>

    private var currentPhotoUri: Uri? = null

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.homepage)

        cameraBtn = findViewById(R.id.cameraBtn)
        uploadBtn = findViewById(R.id.uploadBtn)
        imageView = findViewById(R.id.imageView)

        setupActivityResultLaunchers()

        if (checkPermissions()) {
            setupButtons()
        } else {
            cameraBtn.isEnabled = false
            uploadBtn.isEnabled = false
        }
    }

    private fun setupButtons() {
        cameraBtn.isEnabled = true
        uploadBtn.isEnabled = true

        cameraBtn.setOnClickListener { openCamera() }
        uploadBtn.setOnClickListener { openGallery() }
    }

    private fun setupActivityResultLaunchers() {
        cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                currentPhotoUri?.let { uri ->
                    val bitmap = loadImageFromUri(uri)
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                        runTextRecognition(bitmap)
                    } else {
                        Toast.makeText(this, "Failed to load captured image", Toast.LENGTH_SHORT).show()
                    }
                } ?: run {
                    Toast.makeText(this, "Image URI not found after capture", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Camera capture cancelled or failed", Toast.LENGTH_SHORT).show()
            }
            currentPhotoUri = null
        }

        galleryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val selectedImageUri: Uri? = result.data?.data
                selectedImageUri?.let {
                    val bitmap = loadImageFromUri(it)
                    bitmap?.let { loadedBitmap ->
                        imageView.setImageBitmap(loadedBitmap)
                        runTextRecognition(loadedBitmap)
                    } ?: run {
                        Toast.makeText(this, "Failed to load image from gallery", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun openCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) != null) {
            val photoFile: File? = try {
                createImageFile()
            } catch (ex: IOException) {
                Toast.makeText(this, "Error creating image file: ${ex.message}", Toast.LENGTH_SHORT).show()
                null
            }

            photoFile?.also {
                currentPhotoUri = FileProvider.getUriForFile(
                    this,
                    "${this.packageName}.provider",
                    it
                )
                intent.putExtra(MediaStore.EXTRA_OUTPUT, currentPhotoUri)
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                cameraLauncher.launch(intent)
            }
        } else {
            Toast.makeText(this, "No camera app found to handle capture", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        galleryLauncher.launch(intent)
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        storageDir?.mkdirs()
        return File.createTempFile(
            "JPEG_${timeStamp}_",
            ".jpg",
            storageDir
        )
    }

    private fun checkPermissions(): Boolean {
        val cameraPermission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
        val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (cameraPermission != PackageManager.PERMISSION_GRANTED || storagePermission != PackageManager.PERMISSION_GRANTED) {
            val permissionsToRequest = mutableListOf<String>()
            if (cameraPermission != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.CAMERA)
            }
            if (storagePermission != PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_IMAGES)
                } else {
                    permissionsToRequest.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), REQUEST_PERMISSIONS)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            setupButtons()
        } else {
            Toast.makeText(this, "Permissions are required for camera and storage access.", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadImageFromUri(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source)
            } else {
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    private fun runTextRecognition(bitmap: Bitmap) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val detectedText = visionText.text.lowercase()

                    val realMarkersWords = listOf(
                        listOf("reserve", "bank", "india"),
                        listOf("central", "government"),
                        listOf("i", "promise", "pay"),
                        listOf("rupees"),
                        listOf("mahatma", "gandhi"),
                        listOf("governor")
                    )

                    var matchedPhraseCount = 0

                    for (phraseWords in realMarkersWords) {
                        var matchedWordCount = 0
                        val wordMatchThreshold = 2

                        for (word in phraseWords) {
                            if (detectedText.contains(word)) {
                                matchedWordCount++
                            }
                        }

                        if (matchedWordCount >= wordMatchThreshold) {
                            matchedPhraseCount++
                        }
                    }

                    val requiredPhraseMatches = 3

                    val isFake = matchedPhraseCount < requiredPhraseMatches

                    showResultDialog(bitmap, detectedText, isFake)
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Error during text recognition: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Toast.makeText(this, "Error processing image for recognition: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // MODIFIED FUNCTION: showResultDialog
    private fun showResultDialog(bitmap: Bitmap, resultText: String, isFake: Boolean) {
        // Make sure you have a layout file named dialog_result.xml
        val dialogView = layoutInflater.inflate(R.layout.dialog_result, null)
        val imageView = dialogView.findViewById<ImageView>(R.id.dialogImageView)
        val resultMessage = dialogView.findViewById<TextView>(R.id.resultMessage)
        // Find the TextView for detected text
        val detectedTextView = dialogView.findViewById<TextView>(R.id.detectedText) // Assuming you have this ID in dialog_result.xml

        imageView.setImageBitmap(bitmap)
        resultMessage.text = if (isFake) "❌ Fake Currency Detected" else "✅ Genuine Currency"

        // Hide the TextView showing the detected text
        // Check if the TextView was found before trying to change its visibility
        detectedTextView?.visibility = View.GONE // View.GONE hides the view and removes it from layout space

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // Optional: Helper function to delete the temporary file if needed
    // private fun deleteTempImageFile(uri: Uri) {
    //     try {
    //         contentResolver.delete(uri, null, null)
    //     } catch (e: Exception) {
    //         e.printStackTrace()
    //     }
    // }
}