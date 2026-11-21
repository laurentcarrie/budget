package com.perso.budget

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var db: Db
    private lateinit var list: ListView
    private lateinit var total: TextView
    private var imageCapture: ImageCapture? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var items: List<Receipt> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = Db(this)
        list = findViewById(R.id.list)
        total = findViewById(R.id.total)
        list.setOnItemLongClickListener { _, _, pos, _ ->
            AlertDialog.Builder(this).setMessage("Delete this entry?")
                .setPositiveButton("Delete") { _, _ -> db.delete(items[pos].id); refresh() }
                .setNegativeButton("Cancel", null).show()
            true
        }
        findViewById<Button>(R.id.capture).setOnClickListener { capture() }
        findViewById<Button>(R.id.upload).setOnClickListener { upload() }
        findViewById<Button>(R.id.settings).setOnClickListener { settings() }
        refresh()
        intent.getStringExtra("rescan")?.let { recognize(File(filesDir, File(it).name)) }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else toast("Camera permission is required")
    }

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val provider = f.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.preview).surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capture() {
        val ic = imageCapture ?: return
        val file = File(filesDir, "ticket_${System.currentTimeMillis()}.jpg")
        ic.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(o: ImageCapture.OutputFileResults) { recognize(file) }
                override fun onError(e: ImageCaptureException) { toast("Capture failed: ${e.message}") }
            })
    }

    private fun recognize(file: File) {
        toast("Reading ticket...")
        // Big bold digits are often misread at full size: also try downscaled versions and vote.
        val inputs = listOf(1 to 0f, 2 to 0f, 4 to 0f).mapNotNull { (scale, c) ->
            try { variant(file, scale, c) } catch (e: Exception) { Log.w("Budget", "variant $scale/$c failed", e); null }
        }
        val texts = mutableListOf<String>()
        fun next(i: Int) {
            if (i == inputs.size) {
                run {
                val all = texts.joinToString("\n")
                Log.d("Budget", "OCR (${texts.size} passes):\n$all")
                val shop = Parser.knownShop(all, db.shops()) ?: Parser.shop(texts.firstOrNull() ?: "") ?: ""
                confirm(file, shop, Parser.amount(all), Parser.date(all))
            }
                return
            }
            recognizer.process(inputs[i])
                .addOnSuccessListener { texts += it.text; Log.d("Budget", "pass $i:\n${it.text}"); next(i + 1) }
                .addOnFailureListener { next(i + 1) }
        }
        if (inputs.isEmpty()) confirm(file, "", null, null) else next(0)
    }

    private fun variant(file: File, scale: Int, contrast: Float): InputImage {
        if (scale == 1 && contrast == 0f) return InputImage.fromFilePath(this, android.net.Uri.fromFile(file))
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = scale }
        var bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        if (contrast > 0f) {
            // grayscale + contrast boost around mid grey: thin the ink, whiten the paper
            val cm = android.graphics.ColorMatrix().apply { setSaturation(0f) }
            val t = 128f * (1 - contrast)
            cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, t, 0f, contrast, 0f, 0f, t, 0f, 0f, contrast, 0f, t, 0f, 0f, 0f, 1f, 0f)))
            val out = android.graphics.Bitmap.createBitmap(bmp.width, bmp.height, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(out).drawBitmap(bmp, 0f, 0f,
                android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(cm) })
            bmp = out
        }
        val deg = when (android.media.ExifInterface(file.absolutePath)
            .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        return InputImage.fromBitmap(bmp, deg)
    }

    private fun confirm(file: File, shop: String, amount: Double?, date: String?) {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_confirm, null)
        v.findViewById<android.widget.ImageView>(R.id.photo).setImageBitmap(
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }))
        v.findViewById<EditText>(R.id.date).setText(date ?: SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
        v.findViewById<EditText>(R.id.shop).setText(shop)
        v.findViewById<EditText>(R.id.amount).setText(amount?.let { "%.2f".format(Locale.US, it) } ?: "")
        AlertDialog.Builder(this).setTitle("Check and save").setView(v)
            .setPositiveButton("Save") { _, _ ->
                val a = v.findViewById<EditText>(R.id.amount).text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                val s = v.findViewById<EditText>(R.id.shop).text.toString().ifBlank { "Unknown" }
                db.insert(v.findViewById<EditText>(R.id.date).text.toString().trim(), s, a, file.absolutePath)
                refresh()
            }
            .setNegativeButton("Discard") { _, _ -> file.delete() }
            .setCancelable(false).show()
    }

    private fun refresh() {
        items = db.all()
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            items.map { "${if (it.uploaded) "\u2713 " else ""}${it.date}  ${it.shop}  %.2f EUR".format(Locale.US, it.amount) })
        total.text = "Total: %.2f EUR (%d tickets)".format(Locale.US, items.sumOf { it.amount }, items.size)
    }

    override fun onResume() {
        super.onResume()
        importKeyFile()
    }

    /** `make push-key` drops aws-key.properties in the app's external files dir over USB; read it once, then delete it. */
    private fun importKeyFile() {
        val f = File(getExternalFilesDir(null), "aws-key.properties")
        if (!f.exists()) return
        try {
            val p = java.util.Properties().apply { f.inputStream().use { load(it) } }
            val id = p.getProperty("key_id", "").trim(); val sec = p.getProperty("secret", "").trim()
            if (id.length == 20 && sec.length == 40) { S3.save(this, id, sec); toast("AWS key imported") }
            else toast("aws-key.properties ignored: key ID must be 20 and secret 40 characters")
        } catch (e: Exception) { toast("Key import failed: ${e.message}") }
        finally { f.delete() }
    }

    private fun settings() {
        val cfg = S3.Config(this)
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        v.findViewById<EditText>(R.id.key_id).setText(cfg.keyId)
        v.findViewById<EditText>(R.id.secret).hint =
            if (cfg.secret.isEmpty()) "Secret access key (40 characters)" else "Secret saved (type to replace)"
        AlertDialog.Builder(this).setTitle("Settings").setView(v)
            .setPositiveButton("Save") { _, _ ->
                val id = v.findViewById<EditText>(R.id.key_id).text.toString().trim()
                val sec = v.findViewById<EditText>(R.id.secret).text.toString().trim().ifEmpty { cfg.secret }
                if (id.length != 20 || sec.length != 40) toast("Key ID must be 20 and secret 40 characters")
                else { S3.save(this, id, sec); toast("Saved") }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun upload() {
        val cfg = S3.Config(this)
        if (!cfg.configured) { toast("Enter the AWS key first"); settings(); return }
        val pending = items.filter { !it.uploaded }
        if (pending.isEmpty()) { toast("Nothing to upload"); return }
        toast("Uploading ${pending.size} ticket(s)...")
        Thread {
            var ok = 0; var error: String? = null
            for (r in pending) {
                try {
                    val photo = File(r.photo)
                    val base = photo.nameWithoutExtension
                    if (photo.exists()) S3.put(cfg, "$base.jpg", photo.readBytes(), "image/jpeg")
                    val json = org.json.JSONObject().put("date", r.date).put("shop", r.shop).put("amount", r.amount)
                        .put("currency", "EUR").put("photo", if (photo.exists()) "$base.jpg" else org.json.JSONObject.NULL).toString(2)
                    S3.put(cfg, "$base.json", json.toByteArray(), "application/json")
                    db.markUploaded(r.id); ok++
                } catch (e: Exception) { error = e.message; Log.w("Budget", "upload failed", e); break }
            }
            runOnUiThread {
                refresh()
                toast(if (error == null) "Uploaded $ok ticket(s)" else "Uploaded $ok, failed: $error")
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
