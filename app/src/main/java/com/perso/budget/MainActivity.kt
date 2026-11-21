package com.perso.budget

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
    companion object {
        private var autoSynced = false
        private val syncing = java.util.concurrent.atomic.AtomicBoolean(false)
    }
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
        list.setOnItemClickListener { _, _, pos, _ -> edit(items[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ -> confirmDelete(items[pos]); true }
        findViewById<Button>(R.id.capture).setOnClickListener { capture() }
        findViewById<Button>(R.id.speak).setOnClickListener { speak() }
        findViewById<Button>(R.id.upload).setOnClickListener { sync(false) }
        findViewById<Button>(R.id.settings).setOnClickListener { settings() }
        refresh()
        intent.getStringExtra("rescan")?.let { recognize(File(filesDir, File(it).name)) }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        val granted = r.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when {
            code == 2 -> if (granted) speak() else toast("L'autorisation du micro est n\u00e9cessaire pour la saisie vocale")
            granted -> startCamera()
            else -> toast("L'autorisation de la cam\u00e9ra est n\u00e9cessaire")
        }
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
        val file = File(filesDir, "ticket_${System.currentTimeMillis()}_${deviceSuffix()}.jpg")
        ic.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(o: ImageCapture.OutputFileResults) { recognize(file) }
                override fun onError(e: ImageCaptureException) { toast("\u00c9chec de la capture : ${e.message}") }
            })
    }

    private fun recognize(file: File) {
        toast("Lecture du ticket...")
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

    /** The dialog fields (photo, date, shop with its dropdown of known shops, amount) shared by Save and Edit. */
    private fun ticketView(photo: File, date: String, shop: String, amount: Double?, description: String = ""): android.view.View {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_confirm, null)
        val img = v.findViewById<android.widget.ImageView>(R.id.photo)
        if (photo.exists()) img.setImageBitmap(
            android.graphics.BitmapFactory.decodeFile(photo.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }))
        else img.visibility = android.view.View.GONE
        v.findViewById<EditText>(R.id.date).setText(date)
        val shopField = v.findViewById<AutoCompleteTextView>(R.id.shop)
        shopField.setAdapter(ShopAdapter(this, db.shops().filter { it.isNotBlank() && it != "Unknown" }, shop))
        shopField.setText(shop)
        // Show every known shop as soon as the field is tapped, not only after typing.
        shopField.setOnClickListener { shopField.showDropDown() }
        shopField.setOnFocusChangeListener { _, focused -> if (focused) shopField.showDropDown() }
        v.findViewById<EditText>(R.id.amount).setText(amount?.let { "%.2f".format(Locale.US, it) } ?: "")
        v.findViewById<EditText>(R.id.description).setText(description)
        return v
    }

    private class Fields(val date: String, val shop: String, val amount: Double, val description: String)

    private fun fields(v: android.view.View, fallbackDate: String) = Fields(
        v.findViewById<EditText>(R.id.date).text.toString().trim().ifBlank { fallbackDate },
        v.findViewById<AutoCompleteTextView>(R.id.shop).text.toString().trim().ifBlank { "Unknown" },
        v.findViewById<EditText>(R.id.amount).text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0,
        v.findViewById<EditText>(R.id.description).text.toString().trim())

    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    private fun confirm(file: File, shop: String, amount: Double?, date: String?) {
        val v = ticketView(file, date ?: now(), shop, amount)
        AlertDialog.Builder(this).setTitle("V\u00e9rifier et enregistrer").setView(v)
            .setPositiveButton("Enregistrer") { _, _ ->
                val f = fields(v, now())
                if (!db.insert(file.nameWithoutExtension, f.date, f.shop, f.amount, file.absolutePath, f.description)) toast("Ce ticket est d\u00e9j\u00e0 enregistr\u00e9")
                refresh()
            }
            .setNegativeButton("Abandonner") { _, _ -> file.delete() }
            .setCancelable(false).show()
    }

    /** Tap on a ticket: change its date, shop or amount, or delete it. The change reaches the other devices at the next sync. */
    private fun edit(r: Receipt) {
        val v = ticketView(File(r.photo), r.date, r.shop, r.amount, r.description)
        AlertDialog.Builder(this).setTitle("Modifier le ticket").setView(v)
            .setPositiveButton("Enregistrer") { _, _ ->
                val f = fields(v, r.date)
                if (f.date != r.date || f.shop != r.shop || f.amount != r.amount || f.description != r.description) {
                    db.update(r.id, f.date, f.shop, f.amount, f.description); refresh(); sync(true)
                }
            }
            .setNeutralButton("Supprimer") { _, _ -> confirmDelete(r) }
            .setNegativeButton("Annuler", null).show()
    }

    private fun confirmDelete(r: Receipt) {
        AlertDialog.Builder(this).setMessage("Supprimer ce ticket ?")
            .setPositiveButton("Supprimer") { _, _ -> File(r.photo).delete(); db.softDelete(r.id); refresh() }
            .setNegativeButton("Annuler", null).show()
    }

    private fun refresh() {
        items = db.all()
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            items.map { "${if (it.uploaded) "\u2713 " else ""}${it.date}  ${it.shop}  %.2f EUR".format(Locale.US, it.amount) +
                if (it.description.isNotBlank()) "  \u2013 ${it.description}" else "" })
        total.text = "Total : %.2f EUR (%d tickets)".format(Locale.US, items.sumOf { it.amount }, items.size)
    }

    override fun onResume() {
        super.onResume()
        importKeyFile()
        if (!autoSynced && S3.Config(this).configured) { autoSynced = true; sync(true) }
    }

    /** Random, kept for the life of the install: makes ticket names unique across devices. */
    private fun deviceSuffix(): String {
        val p = getSharedPreferences("app", MODE_PRIVATE)
        return p.getString("device", null) ?: java.util.UUID.randomUUID().toString().take(4).also { p.edit().putString("device", it).apply() }
    }

    /** `make push-key` drops aws-key.properties in the app's external files dir over USB; read it once, then delete it. */
    private fun importKeyFile() {
        val f = File(getExternalFilesDir(null), "aws-key.properties")
        if (!f.exists()) return
        try {
            val p = java.util.Properties().apply { f.inputStream().use { load(it) } }
            val id = p.getProperty("key_id", "").trim(); val sec = p.getProperty("secret", "").trim()
            if (id.length == 20 && sec.length == 40) { S3.save(this, id, sec); toast("Cl\u00e9 AWS import\u00e9e") }
            else toast("aws-key.properties ignor\u00e9 : l'identifiant doit faire 20 caract\u00e8res et le secret 40")
        } catch (e: Exception) { toast("\u00c9chec de l'import de la cl\u00e9 : ${e.message}") }
        finally { f.delete() }
    }

    private fun settings() {
        val cfg = S3.Config(this)
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        v.findViewById<EditText>(R.id.key_id).setText(cfg.keyId)
        v.findViewById<EditText>(R.id.secret).hint =
            if (cfg.secret.isEmpty()) "Cl\u00e9 secr\u00e8te d'acc\u00e8s (40 caract\u00e8res)" else "Secret enregistr\u00e9 (saisir pour remplacer)"
        AlertDialog.Builder(this).setTitle("R\u00e9glages").setView(v)
            .setPositiveButton("Enregistrer") { _, _ ->
                val id = v.findViewById<EditText>(R.id.key_id).text.toString().trim()
                val sec = v.findViewById<EditText>(R.id.secret).text.toString().trim().ifEmpty { cfg.secret }
                if (id.length != 20 || sec.length != 40) toast("L'identifiant doit faire 20 caract\u00e8res et le secret 40")
                else { S3.save(this, id, sec); toast("Enregistr\u00e9") }
            }
            .setNegativeButton("Annuler", null).show()
    }

    private fun sync(auto: Boolean) {
        val cfg = S3.Config(this)
        if (!cfg.configured) { if (!auto) { toast("Saisissez d'abord la cl\u00e9 AWS"); settings() }; return }
        if (!syncing.compareAndSet(false, true)) return
        if (!auto) toast("Synchronisation...")
        Thread {
            var result: Sync.Result? = null; var error: String? = null
            try { result = Sync.run(cfg, db, filesDir) }
            catch (e: Exception) { error = e.message ?: e.toString(); Log.w("Budget", "sync failed", e) }
            finally { syncing.set(false) }
            runOnUiThread {
                refresh()
                when {
                    error != null -> if (!auto) toast("\u00c9chec de la synchronisation : $error")
                    result!!.changed -> toast("Synchro : $result")
                    !auto -> toast("D\u00e9j\u00e0 synchronis\u00e9")
                }
            }
        }.start()
    }

    // ---- Voice entry, in French, in two steps: the amount, then the shop ----
    // One recognizer lives for the whole session: the Google speech service drops the connection when recognizers
    // are created and destroyed in quick succession.
    private var speech: SpeechRecognizer? = null
    private var listening: AlertDialog? = null
    private var spokenAmount: Double? = null
    private var heard = ""
    private var useOffline = true

    private fun speak() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { toast("Pas de reconnaissance vocale sur cet appareil"); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2); return
        }
        endSession()
        spokenAmount = null; heard = ""
        speech = SpeechRecognizer.createSpeechRecognizer(this)
        chooseEngine { offline -> useOffline = offline; listen(true, offline) }
    }

    private fun frenchIntent(offline: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)

    /** Offline when the French pack is installed on the device (Android 13+ can tell), otherwise online. */
    private fun chooseEngine(go: (Boolean) -> Unit) {
        val r = speech ?: return
        if (Build.VERSION.SDK_INT < 33) { go(true); return }
        var answered = false // the service can call back twice
        r.checkRecognitionSupport(frenchIntent(true), mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                if (answered) return
                answered = true
                Log.d("Budget", "speech offline languages: installed=${support.installedOnDeviceLanguages}")
                go(support.installedOnDeviceLanguages.any { it.startsWith("fr") })
            }
            override fun onError(error: Int) {
                if (answered) return
                answered = true
                Log.w("Budget", "speech support check error $error, trying offline first")
                go(true) // if the offline model does not work, listen() falls back to online
            }
        })
    }

    private fun listen(amountStep: Boolean, offline: Boolean) {
        val r = speech ?: return
        val example = if (amountStep) "Dites le montant, par exemple : \u00ab quatorze euros quatre-vingts \u00bb"
                      else "Dites le magasin, par exemple : \u00ab chez Carbone \u00bb"
        listening?.dismiss()
        listening = AlertDialog.Builder(this).setTitle(if (amountStep) "Montant" else "Magasin").setMessage("D\u00e9marrage...\n\n$example")
            .setNegativeButton("Annuler") { _, _ -> endSession() }.setCancelable(false).show()
        fun status(s: String) = runOnUiThread { listening?.setMessage("$s\n\n$example") }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = status("Parlez maintenant")
            override fun onBeginningOfSpeech() = status("Je vous \u00e9coute...")
            override fun onEndOfSpeech() = status("Analyse...")
            override fun onResults(results: Bundle?) {
                val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                Log.d("Budget", "speech heard (offline=$offline): $words")
                listening?.dismiss(); listening = null
                heard(amountStep, words)
            }
            override fun onError(error: Int) {
                Log.w("Budget", "speech error $error (offline=$offline)")
                listening?.dismiss(); listening = null
                // No French offline pack (12, 13) or the offline model understood nothing (7): once, try online
                if (offline && (error == 12 || error == 13 || error == 7)) {
                    useOffline = false
                    toast("Le fran\u00e7ais hors ligne n'a pas march\u00e9, essai en ligne : parlez \u00e0 nouveau")
                    r.cancel(); listen(amountStep, false)
                } else { endSession(); toast("${speechError(error)} ($error)") }
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(frenchIntent(offline))
    }

    private fun endSession() {
        listening?.dismiss(); listening = null
        speech?.destroy(); speech = null
    }

    private fun speechError(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Pas compris, r\u00e9essayez"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Rien entendu, r\u00e9essayez plus pr\u00e8s du micro"
        SpeechRecognizer.ERROR_AUDIO -> "Le micro n'a pas pu \u00eatre lu"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Le service vocal est occup\u00e9, r\u00e9essayez"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "Pas de r\u00e9seau et pas de pack fran\u00e7ais hors ligne : installez-le dans les r\u00e9glages vocaux Google"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "L'autorisation du micro est n\u00e9cessaire"
        else -> "\u00c9chec de la reconnaissance vocale ($code)"
    }

    private fun heard(amountStep: Boolean, words: List<String>) {
        if (words.isEmpty()) { endSession(); toast("Rien compris, r\u00e9essayez"); return }
        if (amountStep) {
            spokenAmount = words.firstNotNullOfOrNull { Spoken.amount(it) }
            heard = "\u00ab ${words.first()} \u00bb"
            if (spokenAmount == null) toast("Montant non compris, vous pouvez le saisir")
            speech?.cancel(); listen(false, useOffline)
        } else {
            heard += "  \u00ab ${words.first()} \u00bb"
            endSession()
            confirmSpoken(Spoken.shop(words, db.shops()) ?: "", spokenAmount)
        }
    }

    /** The ticket said aloud has no photo: it is saved like the others, and synced. */
    private fun confirmSpoken(shop: String, amount: Double?) {
        val v = ticketView(File(""), now(), shop, amount)
        AlertDialog.Builder(this).setTitle("V\u00e9rifier et enregistrer").setMessage("J'ai entendu : $heard").setView(v)
            .setPositiveButton("Enregistrer") { _, _ ->
                val f = fields(v, now())
                db.insert("ticket_${System.currentTimeMillis()}_${deviceSuffix()}", f.date, f.shop, f.amount, "", f.description)
                refresh(); sync(true)
            }
            .setNegativeButton("Annuler", null).setCancelable(false).show()
    }

    override fun onDestroy() { endSession(); super.onDestroy() }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

/**
 * The dropdown of known shops, most used first. It lists every shop while the field still holds the guessed
 * name (or is empty), and narrows to the shops containing what the user types afterwards.
 */
class ShopAdapter(ctx: android.content.Context, private val all: List<String>, private val guessed: String) :
    ArrayAdapter<String>(ctx, android.R.layout.simple_dropdown_item_1line, all.toMutableList()) {
    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(c: CharSequence?): FilterResults {
            val q = c?.toString().orEmpty().trim().lowercase()
            val out = if (q.isEmpty() || q == guessed.trim().lowercase()) all else all.filter { it.lowercase().contains(q) }
            return FilterResults().apply { values = out; count = out.size }
        }
        @Suppress("UNCHECKED_CAST")
        override fun publishResults(c: CharSequence?, r: FilterResults) {
            clear(); addAll(r.values as List<String>); notifyDataSetChanged()
        }
    }
}
