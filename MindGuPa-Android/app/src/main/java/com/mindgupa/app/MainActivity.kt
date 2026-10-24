package com.mindgupa.app

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Base64
import android.util.Patterns
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    companion object {
        const val WORKER_URL = "https://mindgupa.davidgupa.workers.dev"
        const val API_KEY = "Tornado99087944"
        const val MAX_SIDE = 1280
        const val JPEG_QUALITY = 70

        private const val MODE_TEXT = 0
        private const val MODE_VOICE = 1
        private const val MODE_PHOTO = 2
        private const val MODE_DRAW = 3
        private const val COLOR_DEFAULT = Int.MIN_VALUE

        private val ACCENT = Color.parseColor("#3D8BFD")

        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val inflight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("mindgupa", Context.MODE_PRIVATE) }
    private val outboxDir by lazy { File(filesDir, "outbox").also { it.mkdirs() } }
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val darkMode: Boolean get() = prefs.getBoolean("dark", true)
    private val bgColor: Int get() = Color.parseColor(if (darkMode) "#0D0D0D" else "#F5F6F8")
    private val surfaceColor: Int get() = Color.parseColor(if (darkMode) "#1A1A1A" else "#E4E7EC")
    private val mutedColor: Int get() = Color.parseColor(if (darkMode) "#8A8A8A" else "#5B6270")
    private val textColor: Int get() = Color.parseColor(if (darkMode) "#FFFFFF" else "#14161A")
    private val dialogTheme: Int
        get() = if (darkMode) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    private lateinit var root: LinearLayout
    private lateinit var destLabel: TextView
    private lateinit var pendingBtn: Button
    private lateinit var headerView: View
    private lateinit var tabRowView: View
    private lateinit var drawActionRow: View
    private lateinit var fullscreenBtn: ImageButton
    private lateinit var fillBtn: ImageButton
    private var isCanvasFullscreen: Boolean = false
    private lateinit var tabs: List<ImageButton>
    private lateinit var textPanel: View
    private lateinit var voicePanel: View
    private lateinit var photoPanel: View
    private lateinit var drawPanel: View
    private lateinit var drawingCanvas: DrawingCanvasView
    private lateinit var input: EditText
    private lateinit var previewView: PreviewView
    private lateinit var animOverlay: FrameLayout
    private lateinit var flyingEnvelope: ImageView
    private lateinit var paperPlane: ImageView
    private lateinit var successTick: ImageView

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var capturing = false
    private var mode = MODE_TEXT
    private var voiceTarget: String? = null
    private var photoTarget: String? = null
    private var pendingShare: String? = null
    private var settingsDialog: AlertDialog? = null

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                toast("Permiso de cámara denegado")
                showMode(MODE_TEXT)
            }
        }

    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchSpeech() else toast("Permiso de micrófono denegado")
        }

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val spoken = result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()?.trim()
                if (!spoken.isNullOrEmpty()) sendText(spoken, voiceTarget) else toast("No se entendió el audio")
            }
        }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildUi()
        refreshPending()

        if (savedInstanceState == null) handleIntent(intent)

        if (email().isEmpty()) {
            showSettings(true)
        } else {
            showMode(MODE_TEXT)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        retryAll(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        settingsDialog?.dismiss()
        cameraExecutor.shutdown()
    }

    // ---------------------------------------------------------------- settings

    private fun email(): String = prefs.getString("email", "") ?: ""

    private fun extras(): List<String> =
        (prefs.getString("extras", "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun isValidEmail(s: String) = Patterns.EMAIL_ADDRESS.matcher(s).matches()

    private fun updateDestLabel() {
        val e = email()
        val n = extras().size
        destLabel.text = when {
            e.isEmpty() -> "Sin destino configurado"
            n == 0 -> "Destino: $e"
            else -> "Destino: $e  (+$n)"
        }
    }

    private fun showSettings(first: Boolean) {
        val emails = mutableListOf<String>()
        if (email().isNotEmpty()) emails.add(email())
        emails.addAll(extras())
        var defaultIndex = 0
        val wasDark = darkMode

        val scroll = android.widget.ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        scroll.addView(box)

        fun sectionTitle(label: String) = TextView(this).apply {
            text = label
            setTextColor(mutedColor)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(6))
        }

        val darkSwitch = android.widget.Switch(this).apply {
            text = if (wasDark) "Modo claro" else "Modo oscuro"
            setTextColor(textColor)
            textSize = 16f
            isChecked = wasDark
            setOnCheckedChangeListener { _, isChecked ->
                text = if (isChecked) "Modo claro" else "Modo oscuro"
            }
        }
        box.addView(sectionTitle("APARIENCIA"))
        box.addView(darkSwitch)

        box.addView(sectionTitle("CORREOS (TOCA UNO PARA ELEGIRLO POR DEFECTO)"))
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(listBox)

        val addInput = styledInput("Añadir correo", false).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
        }

        fun renderList() {
            listBox.removeAllViews()
            if (emails.isEmpty()) {
                listBox.addView(TextView(this).apply {
                    text = "Aún no hay correos. Añade el primero abajo."
                    setTextColor(mutedColor)
                    textSize = 14f
                    setPadding(0, dp(4), 0, dp(8))
                })
            }
            emails.forEachIndexed { i, address ->
                val selected = i == defaultIndex
                val rowBackground = if (selected) {
                    GradientDrawable().apply {
                        setColor(Color.argb(40, 61, 139, 253))
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(2), ACCENT)
                    }
                } else {
                    rounded(surfaceColor, 12)
                }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rowBackground
                    setPadding(dp(8), dp(6), dp(4), dp(6))
                    setOnClickListener {
                        defaultIndex = i
                        renderList()
                    }
                }
                val radio = android.widget.RadioButton(this).apply {
                    isChecked = selected
                    isClickable = false
                    isFocusable = false
                    buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                }
                row.addView(radio)

                val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                labels.addView(TextView(this).apply {
                    text = address
                    textSize = 15f
                    setTextColor(if (selected) ACCENT else textColor)
                    if (selected) typeface = Typeface.DEFAULT_BOLD
                })
                if (selected) {
                    labels.addView(TextView(this).apply {
                        text = "Por defecto"
                        textSize = 12f
                        setTextColor(ACCENT)
                    })
                }
                row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                val remove = ImageButton(this).apply {
                    setImageResource(R.drawable.ic_close)
                    contentDescription = "Quitar $address"
                    setColorFilter(mutedColor)
                    setBackgroundColor(Color.TRANSPARENT)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setOnClickListener {
                        emails.removeAt(i)
                        if (i < defaultIndex) defaultIndex-- else if (i == defaultIndex) defaultIndex = 0
                        if (defaultIndex >= emails.size) defaultIndex = 0
                        renderList()
                    }
                }
                row.addView(remove, LinearLayout.LayoutParams(dp(40), dp(40)))

                listBox.addView(
                    row,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                )
            }
        }

        fun tryAdd(): Boolean {
            val value = addInput.text.toString().trim()
            if (value.isEmpty()) return true
            if (!isValidEmail(value)) {
                addInput.error = "Correo no válido"
                return false
            }
            if (emails.any { it.equals(value, true) }) {
                addInput.error = "Ya está en la lista"
                return false
            }
            emails.add(value)
            if (emails.size == 1) defaultIndex = 0
            addInput.setText("")
            renderList()
            return true
        }

        renderList()

        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addRow.addView(addInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val addButton = iconButton(R.drawable.ic_add, "Añadir correo", rounded(ACCENT, 12), Color.WHITE, 10).apply {
            setOnClickListener { tryAdd() }
        }
        addRow.addView(addButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        box.addView(addRow)

        val builder = AlertDialog.Builder(this, dialogTheme)
            .setTitle("Ajustes")
            .setView(scroll)
            .setCancelable(!first)
            .setPositiveButton("Guardar", null)
        if (!first) builder.setNegativeButton("Cancelar", null)

        val dialog = builder.create()
        dialog.setOnShowListener {
            if (emails.isEmpty()) {
                dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
                addInput.requestFocus()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!tryAdd()) return@setOnClickListener
                if (emails.isEmpty()) {
                    addInput.error = "Añade al menos un correo"
                    return@setOnClickListener
                }
                val main = emails[defaultIndex]
                val others = emails.filterIndexed { index, _ -> index != defaultIndex }
                prefs.edit()
                    .putString("email", main)
                    .putString("extras", others.joinToString(","))
                    .putBoolean("dark", darkSwitch.isChecked)
                    .apply()
                updateDestLabel()
                dialog.dismiss()
                val share = pendingShare
                if (share != null) {
                    pendingShare = null
                    sendText(share, null)
                    finish()
                } else if (darkSwitch.isChecked != wasDark) {
                    recreate()
                } else if (first) {
                    showMode(MODE_TEXT)
                }
            }
        }
        settingsDialog = dialog
        dialog.show()
    }

    private fun pickTarget(onPick: (String) -> Unit) {
        val main = email()
        val others = extras()
        if (main.isEmpty()) {
            showSettings(true)
            return
        }
        if (others.isEmpty()) {
            onPick(main)
            return
        }
        val all = listOf(main) + others

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
        }

        val title = TextView(this).apply {
            text = "Enviar a"
            setTextColor(textColor)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, dp(4), dp(14))
        }
        box.addView(title)

        var dialog: AlertDialog? = null

        all.forEachIndexed { index, address ->
            val isDefault = index == 0
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(surfaceColor, 12)
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }

            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val emailText = TextView(this).apply {
                text = address
                textSize = 15f
                setTextColor(textColor)
            }
            textLayout.addView(emailText)

            if (isDefault) {
                val subText = TextView(this).apply {
                    text = "Por defecto"
                    textSize = 12f
                    setTextColor(mutedColor)
                }
                textLayout.addView(subText)
            }

            row.addView(textLayout, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                // Iluminar la dirección seleccionada inmediatamente en azul brillante
                row.background = rounded(ACCENT, 12)
                emailText.setTextColor(Color.WHITE)
                emailText.typeface = Typeface.DEFAULT_BOLD

                ui.postDelayed({
                    dialog?.dismiss()
                    onPick(address)
                }, 180)
            }

            box.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            )
        }

        dialog = AlertDialog.Builder(this, dialogTheme)
            .setView(box)
            .create()

        dialog.show()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun oval(color: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(4), stroke)
        }

    private fun styledInput(hintText: String, big: Boolean): EditText =
        EditText(this).apply {
            hint = hintText
            setHintTextColor(mutedColor)
            setTextColor(textColor)
            textSize = if (big) 18f else 16f
            background = rounded(surfaceColor, 12)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

    private fun flatButton(label: String, bg: Int, fg: Int): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(fg)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(bg, 12)
            stateListAnimator = null
        }

    private fun iconButton(
        iconRes: Int,
        description: String,
        bg: GradientDrawable,
        tint: Int,
        paddingDp: Int
    ): ImageButton =
        ImageButton(this).apply {
            setImageResource(iconRes)
            contentDescription = description
            setColorFilter(tint)
            background = bg
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
            stateListAnimator = null
        }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        val title = TextView(this).apply {
            text = "MindGuPa"
            setTextColor(textColor)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        pendingBtn = flatButton("0", Color.parseColor("#5A3A00"), Color.parseColor("#FFB74D")).apply {
            textSize = 13f
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
            contentDescription = "Envíos pendientes"
            val clock = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_queue)!!.mutate()
            clock.setTint(Color.parseColor("#FFB74D"))
            compoundDrawablePadding = dp(6)
            setCompoundDrawablesRelativeWithIntrinsicBounds(clock, null, null, null)
            setOnClickListener { retryAll(false) }
        }
        header.addView(
            pendingBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(8) }
        )

        val settingsBtn = iconButton(R.drawable.ic_settings, "Ajustes", rounded(surfaceColor, 12), textColor, 8).apply {
            setOnClickListener { showSettings(false) }
        }
        header.addView(settingsBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(header)
        headerView = header

        destLabel = TextView(this).apply {
            setTextColor(mutedColor)
            textSize = 13f
            setPadding(dp(16), 0, dp(16), dp(10))
        }
        root.addView(destLabel)
        updateDestLabel()

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        val tabIcons = listOf(R.drawable.ic_text, R.drawable.ic_mic, R.drawable.ic_camera, R.drawable.ic_brush)
        val tabDescriptions = listOf("Nota de texto", "Nota de voz", "Foto", "Dibujo")
        tabs = tabIcons.mapIndexed { index, icon ->
            iconButton(icon, tabDescriptions[index], rounded(surfaceColor, 12), mutedColor, 11).apply {
                setOnClickListener { showMode(index) }
            }
        }
        tabs.forEach {
            tabRow.addView(
                it,
                LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(4); marginEnd = dp(4) }
            )
        }
        root.addView(tabRow)
        tabRowView = tabRow

        val content = FrameLayout(this)
        textPanel = buildTextPanel()
        voicePanel = buildVoicePanel()
        photoPanel = buildPhotoPanel()
        drawPanel = buildDrawPanel()

        animOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = false
        }
        flyingEnvelope = ImageView(this).apply {
            setImageResource(R.drawable.ic_envelope)
            visibility = View.GONE
        }
        paperPlane = ImageView(this).apply {
            setImageResource(R.drawable.ic_paper_plane)
            visibility = View.GONE
        }
        successTick = ImageView(this).apply {
            setImageResource(R.drawable.ic_check_success)
            visibility = View.GONE
        }
        val centerAnimParams = FrameLayout.LayoutParams(dp(110), dp(110), Gravity.CENTER)
        animOverlay.addView(flyingEnvelope, centerAnimParams)
        animOverlay.addView(paperPlane, centerAnimParams)
        animOverlay.addView(successTick, centerAnimParams)

        content.addView(textPanel, FrameLayout.LayoutParams(-1, -1))
        content.addView(voicePanel, FrameLayout.LayoutParams(-1, -1))
        content.addView(photoPanel, FrameLayout.LayoutParams(-1, -1))
        content.addView(drawPanel, FrameLayout.LayoutParams(-1, -1))
        content.addView(animOverlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        onBackPressedDispatcher.addCallback(this) {
            if (isCanvasFullscreen) {
                toggleCanvasFullscreen()
            } else {
                finish()
            }
        }
        runCatching {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkMode
                isAppearanceLightNavigationBars = !darkMode
            }
        }
    }

    private fun toggleCanvasFullscreen() {
        isCanvasFullscreen = !isCanvasFullscreen
        val vis = if (isCanvasFullscreen) View.GONE else View.VISIBLE
        headerView.visibility = vis
        destLabel.visibility = vis
        tabRowView.visibility = vis
        if (::drawActionRow.isInitialized) drawActionRow.visibility = vis
        if (::fullscreenBtn.isInitialized) {
            fullscreenBtn.setImageResource(if (isCanvasFullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
            fullscreenBtn.contentDescription = if (isCanvasFullscreen) "Salir de pantalla completa" else "Pantalla completa"
            fullscreenBtn.background = rounded(if (isCanvasFullscreen) ACCENT else surfaceColor, 8)
            fullscreenBtn.setColorFilter(if (isCanvasFullscreen) Color.WHITE else mutedColor)
        }
    }

    private fun buildTextPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        input = styledInput("Escribe tu idea…", true).apply {
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        panel.addView(input, LinearLayout.LayoutParams(-1, 0, 1f))

        val send = iconButton(R.drawable.ic_send, "Enviar", rounded(ACCENT, 12), Color.WHITE, 14).apply {
            setOnClickListener { submitText(null) }
            setOnLongClickListener {
                pickTarget { target -> submitText(target) }
                true
            }
        }
        panel.addView(send, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(12) })
        return panel
    }

    private fun buildVoicePanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val mic = iconButton(
            R.drawable.ic_mic, "Grabar nota de voz", oval(ACCENT, Color.parseColor("#1F4C8F")), Color.WHITE, 46
        ).apply {
            setOnClickListener { startVoice(null) }
            setOnLongClickListener {
                pickTarget { target -> startVoice(target) }
                true
            }
        }
        panel.addView(mic, LinearLayout.LayoutParams(dp(160), dp(160)))
        val hint = TextView(this).apply {
            text = "Toca y habla. Se envía al terminar."
            setTextColor(mutedColor)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }
        panel.addView(hint)
        return panel
    }

    private fun buildPhotoPanel(): View {
        val panel = FrameLayout(this)
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        panel.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        val shutter = Button(this).apply {
            background = oval(Color.WHITE, Color.parseColor("#88FFFFFF"))
            stateListAnimator = null
            setOnClickListener { takePhoto(null) }
            setOnLongClickListener {
                pickTarget { target -> takePhoto(target) }
                true
            }
        }
        panel.addView(
            shutter,
            FrameLayout.LayoutParams(dp(78), dp(78), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(28) }
        )
        return panel
    }

    private data class CanvasStroke(val path: Path, val color: Int, val width: Float, val isEraser: Boolean = false)

    private data class CanvasShape(
        val type: DrawShape,
        var x1: Float,
        var y1: Float,
        var x2: Float,
        var y2: Float,
        var color: Int,
        var strokeWidth: Float,
        var isFilled: Boolean = false
    ) {
        fun getBounds(): RectF {
            val l = minOf(x1, x2)
            val t = minOf(y1, y2)
            val r = maxOf(x1, x2)
            val b = maxOf(y1, y2)
            return RectF(l, t, maxOf(r, l + 1f), maxOf(b, t + 1f))
        }

        fun toPath(dpFn: (Int) -> Int): Path {
            val p = Path()
            when (type) {
                DrawShape.LINE -> {
                    p.moveTo(x1, y1)
                    p.lineTo(x2, y2)
                }
                DrawShape.RECT -> {
                    val l = minOf(x1, x2)
                    val t = minOf(y1, y2)
                    val r = maxOf(x1, x2)
                    val b = maxOf(y1, y2)
                    p.addRect(l, t, r, b, Path.Direction.CW)
                }
                DrawShape.CIRCLE -> {
                    val l = minOf(x1, x2)
                    val t = minOf(y1, y2)
                    val r = maxOf(x1, x2)
                    val b = maxOf(y1, y2)
                    p.addOval(RectF(l, t, r, b), Path.Direction.CW)
                }
                DrawShape.ARROW -> {
                    p.moveTo(x1, y1)
                    p.lineTo(x2, y2)
                    val dx = (x2 - x1).toDouble()
                    val dy = (y2 - y1).toDouble()
                    val dist = Math.hypot(dx, dy)
                    if (dist > 5) {
                        val angle = Math.atan2(dy, dx)
                        val arrowLen = dpFn(14).coerceAtMost((dist / 2).toInt()).toFloat()
                        val arrowAngle = Math.toRadians(28.0)
                        val ax1 = (x2 - arrowLen * Math.cos(angle - arrowAngle)).toFloat()
                        val ay1 = (y2 - arrowLen * Math.sin(angle - arrowAngle)).toFloat()
                        val ax2 = (x2 - arrowLen * Math.cos(angle + arrowAngle)).toFloat()
                        val ay2 = (y2 - arrowLen * Math.sin(angle + arrowAngle)).toFloat()
                        p.moveTo(ax1, ay1)
                        p.lineTo(x2, y2)
                        p.lineTo(ax2, ay2)
                    }
                }
                else -> {}
            }
            return p
        }
    }

    private data class CanvasText(
        var text: String,
        var x: Float,
        var y: Float,
        var textSizePx: Float,
        var color: Int
    )

    enum class DrawShape {
        SELECT,
        FREEHAND,
        LINE,
        RECT,
        CIRCLE,
        ARROW,
        TEXT,
        ERASER
    }

    private inner class DrawingCanvasView(context: Context) : View(context) {
        private val strokes = mutableListOf<CanvasStroke>()
        private val shapes = mutableListOf<CanvasShape>()
        private val textItems = mutableListOf<CanvasText>()
        private val actions = mutableListOf<Any>()

        private var currentPath: Path? = null
        private var lastX = 0f
        private var lastY = 0f
        private var startX = 0f
        private var startY = 0f
        private var previewEndX = 0f
        private var previewEndY = 0f
        private var isDrawingShape = false

        var selectedShape: CanvasShape? = null
            set(value) {
                field = value
                onSelectionChanged?.invoke(value)
            }
        var onSelectionChanged: ((CanvasShape?) -> Unit)? = null
        var isFillEnabled: Boolean = false
        private var activeHandle = -1
        private var isResizingShape = false
        private var isMovingShape = false
        private var dragStartX = 0f
        private var dragStartY = 0f
        private var origX1 = 0f
        private var origY1 = 0f
        private var origX2 = 0f
        private var origY2 = 0f

        private var activeTextItem: CanvasText? = null
        private var textDragOffsetX = 0f
        private var textDragOffsetY = 0f
        private var isDraggingText = false

        var activeColor: Int = COLOR_DEFAULT
        var strokeWidthPx: Float = dp(4).toFloat()
        var eraserWidthPx: Float = dp(22).toFloat()
        var currentShape: DrawShape = DrawShape.FREEHAND

        private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
        }

        private val textOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            color = ACCENT
        }

        private val selBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            color = ACCENT
            pathEffect = DashPathEffect(floatArrayOf(dp(5).toFloat(), dp(5).toFloat()), 0f)
        }

        private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            color = ACCENT
        }

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        fun isEmpty(): Boolean = strokes.isEmpty() && shapes.isEmpty() && textItems.isEmpty() && currentPath == null && !isDrawingShape

        fun clear() {
            strokes.clear()
            shapes.clear()
            textItems.clear()
            actions.clear()
            selectedShape = null
            activeTextItem = null
            currentPath = null
            isDrawingShape = false
            isDraggingText = false
            isMovingShape = false
            isResizingShape = false
            invalidate()
        }

        fun undo(): Boolean {
            if (actions.isNotEmpty()) {
                val last = actions.removeAt(actions.lastIndex)
                when (last) {
                    is CanvasStroke -> strokes.remove(last)
                    is CanvasShape -> {
                        shapes.remove(last)
                        if (selectedShape === last) selectedShape = null
                    }
                    is CanvasText -> {
                        textItems.remove(last)
                        if (activeTextItem === last) activeTextItem = null
                    }
                }
                invalidate()
                return true
            }
            return false
        }

        fun applyColorToSelected(newColor: Int) {
            selectedShape?.let {
                it.color = newColor
                invalidate()
            }
            activeTextItem?.let {
                it.color = newColor
                invalidate()
            }
        }

        fun addText(item: CanvasText) {
            textItems.add(item)
            actions.add(item)
            activeTextItem = item
            selectedShape = null
            invalidate()
        }

        fun removeText(item: CanvasText) {
            textItems.remove(item)
            actions.remove(item)
            if (activeTextItem === item) activeTextItem = null
            invalidate()
        }

        fun findTextAt(x: Float, y: Float): CanvasText? {
            val pad = dp(16).toFloat()
            for (item in textItems.asReversed()) {
                textPaint.textSize = item.textSizePx
                val lines = item.text.split("\n")
                val maxW = lines.maxOfOrNull { textPaint.measureText(it) } ?: 0f
                val totalH = lines.size * item.textSizePx * 1.25f
                val rect = RectF(
                    item.x - pad,
                    item.y - item.textSizePx - pad,
                    item.x + maxW + pad,
                    item.y + totalH - item.textSizePx + pad
                )
                if (rect.contains(x, y)) {
                    return item
                }
            }
            return null
        }

        fun getHandlesFor(shape: CanvasShape): List<PointF> {
            return if (shape.type == DrawShape.LINE || shape.type == DrawShape.ARROW) {
                listOf(PointF(shape.x1, shape.y1), PointF(shape.x2, shape.y2))
            } else {
                val l = minOf(shape.x1, shape.x2)
                val t = minOf(shape.y1, shape.y2)
                val r = maxOf(shape.x1, shape.x2)
                val b = maxOf(shape.y1, shape.y2)
                listOf(
                    PointF(l, t), // 0: Top-Left
                    PointF(r, t), // 1: Top-Right
                    PointF(r, b), // 2: Bottom-Right
                    PointF(l, b)  // 3: Bottom-Left
                )
            }
        }

        fun findHandleAt(shape: CanvasShape, x: Float, y: Float): Int {
            val hitR = dp(24).toFloat()
            val handles = getHandlesFor(shape)
            for (i in handles.indices) {
                val h = handles[i]
                if (Math.hypot((x - h.x).toDouble(), (y - h.y).toDouble()) <= hitR) {
                    return i
                }
            }
            return -1
        }

        fun findShapeAt(x: Float, y: Float): CanvasShape? {
            val pad = dp(16).toFloat()
            for (shape in shapes.asReversed()) {
                if (shape.type == DrawShape.LINE || shape.type == DrawShape.ARROW) {
                    if (distToSegment(x, y, shape.x1, shape.y1, shape.x2, shape.y2) <= pad) {
                        return shape
                    }
                } else {
                    val b = shape.getBounds()
                    if (x >= b.left - pad && x <= b.right + pad && y >= b.top - pad && y <= b.bottom + pad) {
                        return shape
                    }
                }
            }
            return null
        }

        private fun distToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x2 - x1
            val dy = y2 - y1
            val l2 = dx * dx + dy * dy
            if (l2 == 0f) return Math.hypot((px - x1).toDouble(), (py - y1).toDouble()).toFloat()
            val t = maxOf(0f, minOf(1f, ((px - x1) * dx + (py - y1) * dy) / l2))
            val projX = x1 + t * dx
            val projY = y1 + t * dy
            return Math.hypot((px - projX).toDouble(), (py - projY).toDouble()).toFloat()
        }

        private fun makeShapePath(shape: DrawShape, sx: Float, sy: Float, ex: Float, ey: Float): Path {
            val p = Path()
            when (shape) {
                DrawShape.LINE -> {
                    p.moveTo(sx, sy)
                    p.lineTo(ex, ey)
                }
                DrawShape.RECT -> {
                    val l = minOf(sx, ex)
                    val t = minOf(sy, ey)
                    val r = maxOf(sx, ex)
                    val b = maxOf(sy, ey)
                    p.addRect(l, t, r, b, Path.Direction.CW)
                }
                DrawShape.CIRCLE -> {
                    val l = minOf(sx, ex)
                    val t = minOf(sy, ey)
                    val r = maxOf(sx, ex)
                    val b = maxOf(sy, ey)
                    p.addOval(RectF(l, t, r, b), Path.Direction.CW)
                }
                DrawShape.ARROW -> {
                    p.moveTo(sx, sy)
                    p.lineTo(ex, ey)
                    val dx = (ex - sx).toDouble()
                    val dy = (ey - sy).toDouble()
                    val dist = Math.hypot(dx, dy)
                    if (dist > 5) {
                        val angle = Math.atan2(dy, dx)
                        val arrowLen = dp(14).coerceAtMost((dist / 2).toInt()).toFloat()
                        val arrowAngle = Math.toRadians(28.0)
                        val x1 = (ex - arrowLen * Math.cos(angle - arrowAngle)).toFloat()
                        val y1 = (ey - arrowLen * Math.sin(angle - arrowAngle)).toFloat()
                        val x2 = (ex - arrowLen * Math.cos(angle + arrowAngle)).toFloat()
                        val y2 = (ey - arrowLen * Math.sin(angle + arrowAngle)).toFloat()
                        p.moveTo(x1, y1)
                        p.lineTo(ex, ey)
                        p.lineTo(x2, y2)
                    }
                }
                else -> {}
            }
            return p
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return

            val count = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)

            // Dibujar figuras geométricas
            for (shape in shapes) {
                val path = shape.toPath(::dp)
                if (shape.isFilled && (shape.type == DrawShape.RECT || shape.type == DrawShape.CIRCLE)) {
                    fillPaint.color = if (shape.color == COLOR_DEFAULT) textColor else shape.color
                    canvas.drawPath(path, fillPaint)
                }
                drawPaint.color = if (shape.color == COLOR_DEFAULT) textColor else shape.color
                drawPaint.strokeWidth = shape.strokeWidth
                canvas.drawPath(path, drawPaint)
            }

            // Dibujar trazos libres y borrador
            for (stroke in strokes) {
                if (stroke.isEraser) {
                    eraserPaint.strokeWidth = stroke.width
                    canvas.drawPath(stroke.path, eraserPaint)
                } else {
                    drawPaint.color = if (stroke.color == COLOR_DEFAULT) textColor else stroke.color
                    drawPaint.strokeWidth = stroke.width
                    canvas.drawPath(stroke.path, drawPaint)
                }
            }

            if (currentShape == DrawShape.ERASER) {
                currentPath?.let {
                    eraserPaint.strokeWidth = eraserWidthPx
                    canvas.drawPath(it, eraserPaint)
                }
            } else if (currentShape == DrawShape.FREEHAND) {
                currentPath?.let {
                    drawPaint.color = if (activeColor == COLOR_DEFAULT) textColor else activeColor
                    drawPaint.strokeWidth = strokeWidthPx
                    canvas.drawPath(it, drawPaint)
                }
            } else if (isDrawingShape) {
                val tempPath = makeShapePath(currentShape, startX, startY, previewEndX, previewEndY)
                val curCol = if (activeColor == COLOR_DEFAULT) textColor else activeColor
                if (isFillEnabled && (currentShape == DrawShape.RECT || currentShape == DrawShape.CIRCLE)) {
                    fillPaint.color = curCol
                    canvas.drawPath(tempPath, fillPaint)
                }
                drawPaint.color = curCol
                drawPaint.strokeWidth = strokeWidthPx
                canvas.drawPath(tempPath, drawPaint)
            }

            canvas.restoreToCount(count)

            // Dibujar elementos de texto sobre el lienzo
            for (item in textItems) {
                textPaint.textSize = item.textSizePx
                textPaint.color = if (item.color == COLOR_DEFAULT) textColor else item.color
                val lines = item.text.split("\n")
                var curY = item.y
                for (line in lines) {
                    canvas.drawText(line, item.x, curY, textPaint)
                    curY += item.textSizePx * 1.25f
                }

                if ((currentShape == DrawShape.TEXT || currentShape == DrawShape.SELECT) && item === activeTextItem) {
                    val maxW = lines.maxOfOrNull { textPaint.measureText(it) } ?: 0f
                    val totalH = lines.size * item.textSizePx * 1.25f
                    val boxRect = RectF(
                        item.x - dp(6),
                        item.y - item.textSizePx - dp(2),
                        item.x + maxW + dp(6),
                        item.y + totalH - item.textSizePx + dp(6)
                    )
                    canvas.drawRoundRect(boxRect, dp(4).toFloat(), dp(4).toFloat(), textOutlinePaint)
                }
            }

            // Dibujar recuadro de selección y tiradores (handles) para la figura activa
            selectedShape?.let { s ->
                val b = s.getBounds()
                val pad = dp(8).toFloat()
                val selRect = RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
                canvas.drawRoundRect(selRect, dp(4).toFloat(), dp(4).toFloat(), selBoxPaint)

                val handleR = dp(7).toFloat()
                val handles = getHandlesFor(s)
                for (pt in handles) {
                    canvas.drawCircle(pt.x, pt.y, handleR, handleFillPaint)
                    canvas.drawCircle(pt.x, pt.y, handleR, handleStrokePaint)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    startX = x
                    startY = y
                    lastX = x
                    lastY = y
                    isDraggingText = false
                    isResizingShape = false
                    isMovingShape = false

                    if (currentShape == DrawShape.SELECT) {
                        // 1. Tiradores de la figura seleccionada
                        if (selectedShape != null) {
                            val hIdx = findHandleAt(selectedShape!!, x, y)
                            if (hIdx >= 0) {
                                activeHandle = hIdx
                                isResizingShape = true
                                origX1 = selectedShape!!.x1
                                origY1 = selectedShape!!.y1
                                origX2 = selectedShape!!.x2
                                origY2 = selectedShape!!.y2
                                invalidate()
                                return true
                            }
                        }

                        // 2. Tocar sobre una figura
                        val hitShape = findShapeAt(x, y)
                        if (hitShape != null) {
                            selectedShape = hitShape
                            activeTextItem = null
                            isMovingShape = true
                            dragStartX = x
                            dragStartY = y
                            origX1 = hitShape.x1
                            origY1 = hitShape.y1
                            origX2 = hitShape.x2
                            origY2 = hitShape.y2
                            invalidate()
                            return true
                        }

                        // 3. Tocar sobre un texto
                        val hitText = findTextAt(x, y)
                        if (hitText != null) {
                            activeTextItem = hitText
                            selectedShape = null
                            textDragOffsetX = x - hitText.x
                            textDragOffsetY = y - hitText.y
                            isDraggingText = true
                            invalidate()
                            return true
                        }

                        // 4. Espacio vacío: deseleccionar
                        selectedShape = null
                        activeTextItem = null
                        invalidate()
                        return true
                    } else if (currentShape == DrawShape.TEXT) {
                        val hit = findTextAt(x, y)
                        if (hit != null) {
                            activeTextItem = hit
                            selectedShape = null
                            textDragOffsetX = x - hit.x
                            textDragOffsetY = y - hit.y
                            isDraggingText = true
                            invalidate()
                            return true
                        } else {
                            activeTextItem = null
                            invalidate()
                            return true
                        }
                    } else if (currentShape == DrawShape.FREEHAND || currentShape == DrawShape.ERASER) {
                        selectedShape = null
                        activeTextItem = null
                        currentPath = Path().apply { moveTo(x, y) }
                        invalidate()
                        return true
                    } else {
                        // Dibujo de figuras geométricas (LINE, RECT, CIRCLE, ARROW)
                        // Primero comprobar si se tocan tiradores de la figura actual seleccionada
                        if (selectedShape != null) {
                            val hIdx = findHandleAt(selectedShape!!, x, y)
                            if (hIdx >= 0) {
                                activeHandle = hIdx
                                isResizingShape = true
                                origX1 = selectedShape!!.x1
                                origY1 = selectedShape!!.y1
                                origX2 = selectedShape!!.x2
                                origY2 = selectedShape!!.y2
                                invalidate()
                                return true
                            }
                        }
                        selectedShape = null
                        activeTextItem = null
                        previewEndX = x
                        previewEndY = y
                        isDrawingShape = true
                        invalidate()
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (currentShape == DrawShape.SELECT) {
                        if (isResizingShape && selectedShape != null) {
                            val s = selectedShape!!
                            if (s.type == DrawShape.LINE || s.type == DrawShape.ARROW) {
                                if (activeHandle == 0) {
                                    s.x1 = x
                                    s.y1 = y
                                } else if (activeHandle == 1) {
                                    s.x2 = x
                                    s.y2 = y
                                }
                            } else {
                                val l = minOf(origX1, origX2)
                                val r = maxOf(origX1, origX2)
                                val t = minOf(origY1, origY2)
                                val b = maxOf(origY1, origY2)
                                val minSize = dp(14).toFloat()

                                when (activeHandle) {
                                    0 -> { // Top-Left
                                        val newL = minOf(x, r - minSize)
                                        val newT = minOf(y, b - minSize)
                                        s.x1 = newL
                                        s.y1 = newT
                                        s.x2 = r
                                        s.y2 = b
                                    }
                                    1 -> { // Top-Right
                                        val newR = maxOf(x, l + minSize)
                                        val newT = minOf(y, b - minSize)
                                        s.x1 = l
                                        s.y1 = newT
                                        s.x2 = newR
                                        s.y2 = b
                                    }
                                    2 -> { // Bottom-Right
                                        val newR = maxOf(x, l + minSize)
                                        val newB = maxOf(y, t + minSize)
                                        s.x1 = l
                                        s.y1 = t
                                        s.x2 = newR
                                        s.y2 = newB
                                    }
                                    3 -> { // Bottom-Left
                                        val newL = minOf(x, r - minSize)
                                        val newB = maxOf(y, t + minSize)
                                        s.x1 = newL
                                        s.y1 = t
                                        s.x2 = r
                                        s.y2 = newB
                                    }
                                }
                            }
                            invalidate()
                            return true
                        } else if (isMovingShape && selectedShape != null) {
                            val dx = x - dragStartX
                            val dy = y - dragStartY
                            selectedShape!!.x1 = origX1 + dx
                            selectedShape!!.y1 = origY1 + dy
                            selectedShape!!.x2 = origX2 + dx
                            selectedShape!!.y2 = origY2 + dy
                            invalidate()
                            return true
                        } else if (isDraggingText && activeTextItem != null) {
                            activeTextItem!!.x = x - textDragOffsetX
                            activeTextItem!!.y = y - textDragOffsetY
                            invalidate()
                            return true
                        }
                        return true
                    } else if (currentShape == DrawShape.TEXT) {
                        if (isDraggingText && activeTextItem != null) {
                            activeTextItem!!.x = x - textDragOffsetX
                            activeTextItem!!.y = y - textDragOffsetY
                            invalidate()
                            return true
                        }
                        return true
                    } else if (currentShape == DrawShape.FREEHAND || currentShape == DrawShape.ERASER) {
                        val p = currentPath ?: return false
                        p.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2)
                        lastX = x
                        lastY = y
                    } else if (isResizingShape && selectedShape != null) {
                        val s = selectedShape!!
                        if (s.type == DrawShape.LINE || s.type == DrawShape.ARROW) {
                            if (activeHandle == 0) {
                                s.x1 = x
                                s.y1 = y
                            } else if (activeHandle == 1) {
                                s.x2 = x
                                s.y2 = y
                            }
                        } else {
                            val l = minOf(origX1, origX2)
                            val r = maxOf(origX1, origX2)
                            val t = minOf(origY1, origY2)
                            val b = maxOf(origY1, origY2)
                            val minSize = dp(14).toFloat()

                            when (activeHandle) {
                                0 -> {
                                    s.x1 = minOf(x, r - minSize)
                                    s.y1 = minOf(y, b - minSize)
                                    s.x2 = r
                                    s.y2 = b
                                }
                                1 -> {
                                    s.x1 = l
                                    s.y1 = minOf(y, b - minSize)
                                    s.x2 = maxOf(x, l + minSize)
                                    s.y2 = b
                                }
                                2 -> {
                                    s.x1 = l
                                    s.y1 = t
                                    s.x2 = maxOf(x, l + minSize)
                                    s.y2 = maxOf(y, t + minSize)
                                }
                                3 -> {
                                    s.x1 = minOf(x, r - minSize)
                                    s.y1 = t
                                    s.x2 = r
                                    s.y2 = maxOf(y, t + minSize)
                                }
                            }
                        }
                        invalidate()
                        return true
                    } else {
                        previewEndX = x
                        previewEndY = y
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (currentShape == DrawShape.SELECT) {
                        isResizingShape = false
                        isMovingShape = false
                        isDraggingText = false
                        invalidate()
                        return true
                    } else if (currentShape == DrawShape.TEXT) {
                        val movedDist = Math.hypot((x - startX).toDouble(), (y - startY).toDouble())
                        if (isDraggingText && movedDist > dp(6)) {
                            isDraggingText = false
                            invalidate()
                            return true
                        } else {
                            isDraggingText = false
                            val hit = findTextAt(x, y)
                            if (hit != null) {
                                activeTextItem = hit
                                showTextDialog(hit, hit.x, hit.y)
                            } else {
                                showTextDialog(null, x, y)
                            }
                            invalidate()
                            return true
                        }
                    } else if (currentShape == DrawShape.ERASER) {
                        val p = currentPath ?: return false
                        p.lineTo(x, y)
                        val stroke = CanvasStroke(p, 0, eraserWidthPx, isEraser = true)
                        strokes.add(stroke)
                        actions.add(stroke)
                        currentPath = null
                    } else if (currentShape == DrawShape.FREEHAND) {
                        val p = currentPath ?: return false
                        p.lineTo(x, y)
                        val stroke = CanvasStroke(p, activeColor, strokeWidthPx, isEraser = false)
                        strokes.add(stroke)
                        actions.add(stroke)
                        currentPath = null
                    } else if (isResizingShape) {
                        isResizingShape = false
                    } else if (isDrawingShape) {
                        val dist = Math.hypot((x - startX).toDouble(), (y - startY).toDouble())
                        if (dist > dp(6)) {
                            val newShape = CanvasShape(currentShape, startX, startY, x, y, activeColor, strokeWidthPx, isFilled = isFillEnabled)
                            shapes.add(newShape)
                            actions.add(newShape)
                            selectedShape = newShape
                        }
                        isDrawingShape = false
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    currentPath = null
                    isDrawingShape = false
                    isDraggingText = false
                    isMovingShape = false
                    isResizingShape = false
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        fun toJpegBase64(exportBgColor: Int): String? {
            if (width <= 0 || height <= 0 || isEmpty()) return null
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(exportBgColor)

            val count = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)

            val exportFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            }

            val exportPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }

            // Dibujar figuras geométricas
            for (shape in shapes) {
                val path = shape.toPath(::dp)
                if (shape.isFilled && (shape.type == DrawShape.RECT || shape.type == DrawShape.CIRCLE)) {
                    exportFillPaint.color = if (shape.color == COLOR_DEFAULT) Color.parseColor("#0F172A") else shape.color
                    canvas.drawPath(path, exportFillPaint)
                }
                exportPaint.color = if (shape.color == COLOR_DEFAULT) Color.parseColor("#0F172A") else shape.color
                exportPaint.strokeWidth = shape.strokeWidth
                canvas.drawPath(path, exportPaint)
            }

            // Dibujar trazos libres y borrador
            for (stroke in strokes) {
                if (stroke.isEraser) {
                    eraserPaint.strokeWidth = stroke.width
                    canvas.drawPath(stroke.path, eraserPaint)
                } else {
                    exportPaint.color = if (stroke.color == COLOR_DEFAULT) Color.parseColor("#0F172A") else stroke.color
                    exportPaint.strokeWidth = stroke.width
                    canvas.drawPath(stroke.path, exportPaint)
                }
            }

            canvas.restoreToCount(count)

            // Dibujar textos en la imagen JPG exportada
            val exportTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.DEFAULT_BOLD
            }
            for (item in textItems) {
                exportTextPaint.textSize = item.textSizePx
                exportTextPaint.color = if (item.color == COLOR_DEFAULT) Color.parseColor("#0F172A") else item.color
                val lines = item.text.split("\n")
                var curY = item.y
                for (line in lines) {
                    canvas.drawText(line, item.x, curY, exportTextPaint)
                    curY += item.textSizePx * 1.25f
                }
            }

            val maxDim = maxOf(width, height)
            val finalBitmap = if (maxDim > MAX_SIDE) {
                val scale = MAX_SIDE.toFloat() / maxDim
                val matrix = Matrix().apply { postScale(scale, scale) }
                val scaled = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
                bitmap.recycle()
                scaled
            } else {
                bitmap
            }

            val out = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            finalBitmap.recycle()
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }

    private fun showTextDialog(targetItem: CanvasText?, spawnX: Float, spawnY: Float) {
        val isEdit = targetItem != null
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        val inputEdit = EditText(this).apply {
            hint = "Escribe tu texto..."
            setText(targetItem?.text ?: "")
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            textSize = 16f
            background = rounded(surfaceColor, 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isSingleLine = false
            maxLines = 4
        }
        rootLayout.addView(inputEdit, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        val sizeLabel = TextView(this).apply {
            text = "TAMAÑO DE LETRA"
            setTextColor(mutedColor)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(8))
        }
        rootLayout.addView(sizeLabel)

        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        data class SizeOption(val label: String, val sp: Float)
        val sizeOptions = listOf(
            SizeOption("S", 16f),
            SizeOption("M", 24f),
            SizeOption("L", 34f),
            SizeOption("XL", 46f)
        )

        var selectedSizeSp = targetItem?.let {
            it.textSizePx / resources.displayMetrics.scaledDensity
        } ?: 24f

        val sizeButtons = mutableListOf<Button>()

        fun updateSizeButtons() {
            sizeOptions.forEachIndexed { i, opt ->
                val btn = sizeButtons[i]
                val active = Math.abs(opt.sp - selectedSizeSp) < 2f
                btn.background = rounded(if (active) ACCENT else surfaceColor, 8)
                btn.setTextColor(if (active) Color.WHITE else mutedColor)
            }
        }

        sizeOptions.forEach { opt ->
            val btn = Button(this).apply {
                text = opt.label
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                isAllCaps = false
                setOnClickListener {
                    it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    selectedSizeSp = opt.sp
                    updateSizeButtons()
                }
            }
            sizeButtons.add(btn)
            sizeRow.addView(btn, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                if (opt != sizeOptions.last()) marginEnd = dp(8)
            })
        }
        updateSizeButtons()
        rootLayout.addView(sizeRow)

        val builder = AlertDialog.Builder(this)
            .setTitle(if (isEdit) "Editar texto" else "Añadir texto")
            .setView(rootLayout)
            .setPositiveButton(if (isEdit) "Guardar" else "Añadir") { _, _ ->
                val txt = inputEdit.text.toString().trim()
                if (txt.isNotEmpty()) {
                    val sizePx = selectedSizeSp * resources.displayMetrics.scaledDensity
                    if (isEdit) {
                        targetItem!!.text = txt
                        targetItem.textSizePx = sizePx
                    } else {
                        val newText = CanvasText(
                            text = txt,
                            x = spawnX,
                            y = spawnY,
                            textSizePx = sizePx,
                            color = drawingCanvas.activeColor
                        )
                        drawingCanvas.addText(newText)
                    }
                    drawingCanvas.invalidate()
                }
            }
            .setNegativeButton("Cancelar", null)

        if (isEdit) {
            builder.setNeutralButton("Eliminar") { _, _ ->
                drawingCanvas.removeText(targetItem!!)
                drawingCanvas.invalidate()
            }
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            inputEdit.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(inputEdit, InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

    private fun buildDrawPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        // Toolbar: Fila 1 - Puntero + Figuras geométricas + Texto + Borrador + Deshacer
        val shapeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        data class ShapeBtnInfo(val shape: DrawShape, val iconRes: Int, val desc: String)
        val shapeList = listOf(
            ShapeBtnInfo(DrawShape.SELECT, R.drawable.ic_select, "Seleccionar y mover"),
            ShapeBtnInfo(DrawShape.FREEHAND, R.drawable.ic_brush, "Mano alzada"),
            ShapeBtnInfo(DrawShape.LINE, R.drawable.ic_shape_line, "Línea recta"),
            ShapeBtnInfo(DrawShape.RECT, R.drawable.ic_shape_rect, "Rectángulo"),
            ShapeBtnInfo(DrawShape.CIRCLE, R.drawable.ic_shape_circle, "Círculo"),
            ShapeBtnInfo(DrawShape.ARROW, R.drawable.ic_shape_arrow, "Flecha"),
            ShapeBtnInfo(DrawShape.TEXT, R.drawable.ic_shape_text, "Texto"),
            ShapeBtnInfo(DrawShape.ERASER, R.drawable.ic_eraser, "Borrador manual")
        )

        val shapeButtons = mutableListOf<ImageButton>()

        fun updateShapeButtons() {
            shapeList.forEachIndexed { idx, item ->
                val btn = shapeButtons[idx]
                val selected = drawingCanvas.currentShape == item.shape
                btn.background = rounded(if (selected) ACCENT else surfaceColor, 10)
                btn.setColorFilter(if (selected) Color.WHITE else mutedColor)
            }
        }

        shapeList.forEach { item ->
            val btn = iconButton(item.iconRes, item.desc, rounded(surfaceColor, 10), mutedColor, 6).apply {
                setOnClickListener {
                    it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    drawingCanvas.currentShape = item.shape
                    updateShapeButtons()
                }
            }
            shapeButtons.add(btn)
            shapeRow.addView(btn, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(2) })
        }

        // Espaciador flexible
        shapeRow.addView(View(this), LinearLayout.LayoutParams(0, dp(32), 1f))

        val undoBtn = iconButton(R.drawable.ic_undo, "Deshacer trazo", rounded(surfaceColor, 10), mutedColor, 6).apply {
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                drawingCanvas.undo()
            }
        }
        shapeRow.addView(undoBtn, LinearLayout.LayoutParams(dp(32), dp(32)))

        panel.addView(shapeRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

        // Toolbar: Fila 2 - Paleta de colores + Relleno + Pantalla Completa
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val colors = listOf(
            COLOR_DEFAULT,
            Color.parseColor("#2563EB"), // Azul
            Color.parseColor("#DC2626"), // Rojo
            Color.parseColor("#16A34A"), // Verde
            Color.parseColor("#EAB308"), // Amarillo
            Color.parseColor("#EA580C"), // Naranja
            Color.parseColor("#9333EA")  // Púrpura
        )

        val colorViews = mutableListOf<View>()

        fun makeSwatchDrawable(col: Int, isSelected: Boolean): Drawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                val displayColor = if (col == COLOR_DEFAULT) {
                    if (darkMode) Color.WHITE else Color.parseColor("#0F172A")
                } else col
                setColor(displayColor)
                if (isSelected) {
                    setStroke(dp(3), ACCENT)
                } else {
                    setStroke(dp(1), if (darkMode) Color.parseColor("#475569") else Color.parseColor("#CBD5E1"))
                }
            }
        }

        fun updateColorViews() {
            colors.forEachIndexed { idx, c ->
                val v = colorViews[idx]
                v.background = makeSwatchDrawable(c, drawingCanvas.activeColor == c)
            }
        }

        colors.forEach { c ->
            val swatch = View(this).apply {
                background = makeSwatchDrawable(c, c == COLOR_DEFAULT)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    drawingCanvas.activeColor = c
                    if (drawingCanvas.currentShape == DrawShape.ERASER) {
                        drawingCanvas.currentShape = DrawShape.FREEHAND
                        updateShapeButtons()
                    }
                    drawingCanvas.applyColorToSelected(c)
                    updateColorViews()
                }
            }
            colorViews.add(swatch)
            colorRow.addView(swatch, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(6) })
        }

        // Espaciador flexible
        colorRow.addView(View(this), LinearLayout.LayoutParams(0, dp(32), 1f))

        fun updateFillBtnUi() {
            val isFilled = drawingCanvas.selectedShape?.isFilled ?: drawingCanvas.isFillEnabled
            fillBtn.background = rounded(if (isFilled) ACCENT else surfaceColor, 8)
            fillBtn.setColorFilter(if (isFilled) Color.WHITE else mutedColor)
        }

        fillBtn = iconButton(R.drawable.ic_fill, "Relleno de figura", rounded(surfaceColor, 8), mutedColor, 6).apply {
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                if (drawingCanvas.selectedShape != null) {
                    val s = drawingCanvas.selectedShape!!
                    s.isFilled = !s.isFilled
                    drawingCanvas.invalidate()
                } else {
                    drawingCanvas.isFillEnabled = !drawingCanvas.isFillEnabled
                }
                updateFillBtnUi()
            }
        }
        colorRow.addView(fillBtn, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(6) })

        fullscreenBtn = iconButton(R.drawable.ic_fullscreen, "Pantalla completa", rounded(surfaceColor, 8), mutedColor, 6).apply {
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                toggleCanvasFullscreen()
            }
        }
        colorRow.addView(fullscreenBtn, LinearLayout.LayoutParams(dp(32), dp(32)))

        panel.addView(colorRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

        drawingCanvas = DrawingCanvasView(this).apply {
            background = rounded(surfaceColor, 16)
            strokeWidthPx = dp(4).toFloat()
        }
        drawingCanvas.onSelectionChanged = {
            updateFillBtnUi()
        }
        panel.addView(drawingCanvas, LinearLayout.LayoutParams(-1, 0, 1f))

        updateShapeButtons()
        updateColorViews()
        updateFillBtnUi()

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        drawActionRow = actionRow

        val clearBtn = iconButton(R.drawable.ic_clear, "Limpiar lienzo", rounded(surfaceColor, 12), mutedColor, 14).apply {
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                drawingCanvas.clear()
            }
        }
        actionRow.addView(clearBtn, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(12) })

        val sendBtn = iconButton(R.drawable.ic_send, "Enviar dibujo", rounded(ACCENT, 12), Color.WHITE, 14).apply {
            setOnClickListener { sendDrawing(null) }
            setOnLongClickListener {
                pickTarget { target -> sendDrawing(target) }
                true
            }
        }
        actionRow.addView(sendBtn, LinearLayout.LayoutParams(0, dp(56), 1f))

        panel.addView(actionRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        return panel
    }

    private fun sendDrawing(target: String?) {
        if (drawingCanvas.isEmpty()) {
            toast("Dibuja algo primero")
            return
        }
        if (email().isEmpty()) {
            showSettings(true)
            return
        }
        val to = target ?: email()
        val b64 = drawingCanvas.toJpegBase64(exportBgColor = Color.WHITE)
        if (b64 != null) {
            drawingCanvas.clear()
            enqueue(to, "MindGuPa: Dibujo", "", b64)
        } else {
            toast("No se pudo generar la imagen")
        }
    }

    private fun showMode(newMode: Int) {
        if (isCanvasFullscreen && newMode != MODE_DRAW) {
            toggleCanvasFullscreen()
        }
        mode = newMode
        textPanel.visibility = if (newMode == MODE_TEXT) View.VISIBLE else View.GONE
        voicePanel.visibility = if (newMode == MODE_VOICE) View.VISIBLE else View.GONE
        photoPanel.visibility = if (newMode == MODE_PHOTO) View.VISIBLE else View.GONE
        drawPanel.visibility = if (newMode == MODE_DRAW) View.VISIBLE else View.GONE
        tabs.forEachIndexed { i, b ->
            val active = i == newMode
            b.background = rounded(if (active) ACCENT else surfaceColor, 12)
            b.setColorFilter(if (active) Color.WHITE else mutedColor)
        }

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (newMode == MODE_TEXT) {
            input.post {
                input.requestFocus()
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        } else {
            imm.hideSoftInputFromWindow(root.windowToken, 0)
        }

        if (newMode == MODE_PHOTO) {
            if (hasPermission(Manifest.permission.CAMERA)) startCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            stopCamera()
        }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) {
        ui.post {
            if (!isFinishing && !isDestroyed) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- capture: text

    private fun submitText(target: String?) {
        val text = input.text.toString().trim()
        if (text.isEmpty()) {
            toast("Escribe algo primero")
            return
        }
        if (email().isEmpty()) {
            showSettings(true)
            return
        }
        input.setText("")
        sendText(text, target)
    }

    private fun subjectFor(text: String): String =
        "MindGuPa: " + text.lineSequence().first().trim().take(60)

    private fun sendText(text: String, target: String?) {
        val to = target ?: email()
        if (to.isEmpty()) {
            toast("Configura primero tu correo")
            return
        }
        enqueue(to, subjectFor(text), text, null)
    }

    // ---------------------------------------------------------------- capture: voice

    private fun startVoice(target: String?) {
        if (email().isEmpty()) {
            showSettings(true)
            return
        }
        voiceTarget = target
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) launchSpeech()
        else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun launchSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla ahora")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            toast("Este dispositivo no tiene reconocimiento de voz")
        }
    }

    // ---------------------------------------------------------------- capture: photo

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed || mode != MODE_PHOTO) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                val selector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 960),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(selector)
                    .build()
                imageCapture = capture

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            } catch (e: Exception) {
                toast("No se pudo iniciar la cámara")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        imageCapture = null
    }

    private fun takePhoto(target: String?) {
        if (email().isEmpty()) {
            showSettings(true)
            return
        }
        val capture = imageCapture ?: return
        if (capturing) return
        capturing = true
        photoTarget = target
        val to = target ?: email()

        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val b64 = encodeImage(image)
                    enqueue(to, "MindGuPa: Foto", "", b64)
                } catch (e: Exception) {
                    toast("No se pudo procesar la foto")
                } finally {
                    image.close()
                    capturing = false
                }
            }

            override fun onError(exception: ImageCaptureException) {
                capturing = false
                toast("Error al capturar la foto")
            }
        })
    }

    private fun encodeImage(image: ImageProxy): String {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val rotation = image.imageInfo.rotationDegrees

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IllegalStateException("decode")

        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            postScale(scale, scale)
        }
        val finalBitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

        val out = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (finalBitmap !== decoded) decoded.recycle()
        finalBitmap.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ---------------------------------------------------------------- share

    private fun handleIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) return
        val type = intent.type ?: return
        if (!type.startsWith("text/")) return

        val body = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        if (body.isEmpty()) return

        val text = if (subject.isNotEmpty() && !body.contains(subject)) "$subject\n\n$body" else body
        setIntent(Intent())

        if (email().isEmpty()) {
            pendingShare = text
            return
        }
        sendText(text, null)
        finish()
    }

    // ---------------------------------------------------------------- outbox + network

    private fun showFlyAnimation() {
        ui.post {
            animOverlay.visibility = View.VISIBLE
            successTick.visibility = View.GONE
            paperPlane.visibility = View.GONE

            flyingEnvelope.apply {
                animate().cancel()
                visibility = View.VISIBLE
                alpha = 0f
                scaleX = 0.6f
                scaleY = 0.6f
                translationX = 0f
                translationY = dp(35).toFloat()
                rotation = -6f

                // 1. Sobre aparece en el centro
                animate()
                    .alpha(1f)
                    .scaleX(1.15f)
                    .scaleY(1.15f)
                    .translationY(0f)
                    .rotation(0f)
                    .setDuration(320)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        // 2. Sobre se pliega (se comprime como doblando el papel)
                        animate()
                            .scaleX(0.05f)
                            .scaleY(0.9f)
                            .alpha(0.3f)
                            .setDuration(220)
                            .withEndAction {
                                visibility = View.GONE

                                // 3. Se transforma en el avión de papel
                                paperPlane.apply {
                                    animate().cancel()
                                    visibility = View.VISIBLE
                                    alpha = 0.6f
                                    scaleX = 0.1f
                                    scaleY = 0.5f
                                    translationX = 0f
                                    translationY = 0f
                                    rotation = -5f

                                    // Despliegue del avión listo para despegar
                                    animate()
                                        .alpha(1f)
                                        .scaleX(1.15f)
                                        .scaleY(1.15f)
                                        .rotation(12f)
                                        .setDuration(180)
                                        .setInterpolator(OvershootInterpolator(1.6f))
                                        .withEndAction {
                                            // 4. Vuelo ascendente perdiéndose en el horizonte
                                            animate()
                                                .translationX(dp(180).toFloat())
                                                .translationY(-dp(380).toFloat())
                                                .rotation(28f)
                                                .scaleX(0.08f)
                                                .scaleY(0.08f)
                                                .alpha(0f)
                                                .setDuration(950)
                                                .setInterpolator(AccelerateInterpolator(1.3f))
                                                .withEndAction {
                                                    visibility = View.GONE
                                                }
                                                .start()
                                        }
                                        .start()
                                }
                            }
                            .start()
                    }
                    .start()
            }
        }
    }

    private fun showSuccessTick() {
        ui.post {
            animOverlay.visibility = View.VISIBLE
            flyingEnvelope.visibility = View.GONE
            paperPlane.visibility = View.GONE

            successTick.apply {
                animate().cancel()
                visibility = View.VISIBLE
                alpha = 0f
                scaleX = 0.2f
                scaleY = 0.2f

                animate()
                    .alpha(1f)
                    .scaleX(1.15f)
                    .scaleY(1.15f)
                    .setDuration(380)
                    .setInterpolator(OvershootInterpolator(2.2f))
                    .withEndAction {
                        animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(140)
                            .start()

                        ui.postDelayed({
                            if (!isFinishing && !isDestroyed) {
                                animate()
                                    .alpha(0f)
                                    .scaleX(0.7f)
                                    .scaleY(0.7f)
                                    .setDuration(300)
                                    .withEndAction {
                                        visibility = View.GONE
                                        animOverlay.visibility = View.GONE
                                    }
                                    .start()
                            }
                        }, 1000)
                    }
                    .start()
            }
        }
    }

    private fun hideAnimationOverlay() {
        ui.post {
            animOverlay.visibility = View.GONE
            flyingEnvelope.visibility = View.GONE
            paperPlane.visibility = View.GONE
            successTick.visibility = View.GONE
        }
    }

    private fun enqueue(to: String, subject: String, text: String, imageB64: String?) {
        showFlyAnimation()
        val startTime = System.currentTimeMillis()
        appScope.launch {
            val json = JSONObject().apply {
                put("to", JSONArray().put(to))
                put("subject", subject)
                put("text", text)
                if (imageB64 != null) put("image", imageB64)
            }
            val file = File(outboxDir, "${System.currentTimeMillis()}-${UUID.randomUUID()}.json")
            file.writeText(json.toString(), Charsets.UTF_8)
            ui.post { refreshPending() }

            val ok = deliver(file)

            // Esperar a que el avión termine de perderse en el horizonte antes de mostrar el tick
            val elapsed = System.currentTimeMillis() - startTime
            val remaining = 1650L - elapsed
            if (remaining > 0) {
                delay(remaining)
            }

            if (ok) {
                showSuccessTick()
            } else {
                hideAnimationOverlay()
                toast("Sin conexión: guardado en la cola")
            }
            ui.post { refreshPending() }
        }
    }

    private fun deliver(file: File): Boolean {
        if (!inflight.add(file.name)) return false
        try {
            val body = file.readBytes()
            val conn = URL(WORKER_URL).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 20000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("X-MindGuPa-Key", API_KEY)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                (if (code < 400) conn.inputStream else conn.errorStream)?.close()
                if (code in 200..299) {
                    file.delete()
                    return true
                }
                return false
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            return false
        } finally {
            inflight.remove(file.name)
        }
    }

    private fun retryAll(silent: Boolean) {
        val files = outboxDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) {
            if (!silent) toast("No hay envíos pendientes")
            return
        }
        appScope.launch {
            var sent = 0
            for (f in files) {
                if (deliver(f)) sent++
            }
            if (!silent) toast("Reenviados $sent de ${files.size}")
            ui.post { refreshPending() }
        }
    }

    private fun refreshPending() {
        if (isFinishing || isDestroyed) return
        val n = outboxDir.list()?.size ?: 0
        pendingBtn.text = "$n"
        pendingBtn.visibility = if (n > 0) View.VISIBLE else View.GONE
    }
}
