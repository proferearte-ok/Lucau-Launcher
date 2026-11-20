package com.tvlauncher.util

import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.BlurMaskFilter
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.tvlauncher.R
import com.tvlauncher.model.WallpaperConfig
import com.tvlauncher.model.WallpaperPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import java.util.concurrent.Executors

object WallpaperManagerUtil {

    private const val PREFS_NAME = "wallpaper_prefs"
    private const val KEY_CONFIG = "wallpaper_config"
    private const val KEY_CURRENT_PRESET = "current_preset"
    private const val PRESET_DIR = "wallpapers"
    private const val TAG = "WallpaperManager"

    private var slideshowHandler: Handler? = null
    private var slideshowRunnable: Runnable? = null
    private var currentSlideshowIndex = 0

    // ==================== CONFIGURACIÓN ====================

    fun getConfig(context: Context): WallpaperConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CONFIG, null)
        return if (json != null) {
            WallpaperConfig.fromJson(json)
        } else {
            WallpaperConfig.getDefault()
        }
    }

    fun saveConfig(context: Context, config: WallpaperConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONFIG, config.toJson()).apply()
    }

    fun getCurrentPreset(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CURRENT_PRESET, null)
    }

    fun setCurrentPreset(context: Context, presetId: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CURRENT_PRESET, presetId).apply()
    }

    // ==================== PRESETS ====================

    fun getBuiltInPresets(context: Context): List<WallpaperPreset> {
        return listOf(
            WallpaperPreset(
                id = "gradient_teal",
                name = "Gradiente Teal",
                type = WallpaperPreset.Type.GRADIENT,
                previewColor = 0xFF3DD6D0,
                gradientColors = intArrayOf(0xFF0A1F2E, 0xFF1A3A4A, 0xFF3DD6D0),
                gradientAngle = 135
            ),
            WallpaperPreset(
                id = "gradient_dark",
                name = "Gradiente Oscuro",
                type = WallpaperPreset.Type.GRADIENT,
                previewColor = 0xFF1A1A2E,
                gradientColors = intArrayOf(0xFF0A0A12, 0xFF1A1A2E, 0xFF16213E),
                gradientAngle = 45
            ),
            WallpaperPreset(
                id = "gradient_sunset",
                name = "Atardecer",
                type = WallpaperPreset.Type.GRADIENT,
                previewColor = 0xFFFF6B35,
                gradientColors = intArrayOf(0xFF0D0D0D, 0xFF3E1E2A, 0xFFFF6B35),
                gradientAngle = 180
            ),
            WallpaperPreset(
                id = "gradient_ocean",
                name = "Océano",
                type = WallpaperPreset.Type.GRADIENT,
                previewColor = 0xFF0077B6,
                gradientColors = intArrayOf(0xFF001D3D, 0xFF003566, 0xFF0077B6),
                gradientAngle = 90
            ),
            WallpaperPreset(
                id = "gradient_forest",
                name = "Bosque",
                type = WallpaperPreset.Type.GRADIENT,
                previewColor = 0xFF2E7D32,
                gradientColors = intArrayOf(0xFF0D1B0D, 0xFF1B3D1B, 0xFF2E7D32),
                gradientAngle = 135
            ),
            WallpaperPreset(
                id = "solid_dark",
                name = "Negro Puro",
                type = WallpaperPreset.Type.SOLID,
                previewColor = 0xFF000000,
                solidColor = 0xFF000000
            ),
            WallpaperPreset(
                id = "solid_navy",
                name = "Azul Marino",
                type = WallpaperPreset.Type.SOLID,
                previewColor = 0xFF0A1628,
                solidColor = 0xFF0A1628
            )
        )
    }

    // ==================== APLICAR WALLPAPER ====================

    fun applyWallpaper(context: Context, view: android.view.View, config: WallpaperConfig = getConfig(context)) {
        when (config.source) {
            WallpaperConfig.Source.PRESET -> applyPreset(context, view, config)
            WallpaperConfig.Source.LOCAL_FILE -> applyLocalFile(context, view, config)
            WallpaperConfig.Source.REMOTE_URL -> applyRemoteUrl(context, view, config)
            WallpaperConfig.Source.SLIDESHOW -> startSlideshow(context, view, config)
        }
    }

    private fun applyPreset(context: Context, view: android.view.View, config: WallpaperConfig) {
        val presets = getBuiltInPresets(context)
        val preset = presets.find { it.id == config.presetId } ?: presets.first()

        val drawable = when (preset.type) {
            WallpaperPreset.Type.GRADIENT -> createGradientDrawable(preset)
            WallpaperPreset.Type.SOLID -> createSolidDrawable(preset)
        }

        applyDrawableToView(view, drawable, config)
    }

    private fun applyLocalFile(context: Context, view: android.view.View, config: WallpaperConfig) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val file = File(config.localFilePath)
                if (!file.exists()) {
                    Log.w(TAG, "Local wallpaper file not found: ${config.localFilePath}")
                    return@launch
                }

                val bitmap = BitmapFactory.decodeStream(FileInputStream(file))
                if (bitmap == null) {
                    Log.w(TAG, "Failed to decode local wallpaper")
                    return@launch
                }

                val processed = processBitmap(bitmap, config)
                val drawable = BitmapDrawable(context.resources, processed)

                Handler(Looper.getMainLooper()).post {
                    applyDrawableToView(view, drawable, config)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error applying local wallpaper", e)
            }
        }
    }

    private fun applyRemoteUrl(context: Context, view: android.view.View, config: WallpaperConfig) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(config.remoteUrl)
                val inputStream = url.openStream()
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                if (bitmap == null) {
                    Log.w(TAG, "Failed to decode remote wallpaper")
                    return@launch
                }

                val processed = processBitmap(bitmap, config)
                val drawable = BitmapDrawable(context.resources, processed)

                // Cache locally
                cacheRemoteWallpaper(context, bitmap, config.remoteUrl)

                Handler(Looper.getMainLooper()).post {
                    applyDrawableToView(view, drawable, config)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error applying remote wallpaper", e)
                // Try cached version
                tryLoadCachedRemote(context, view, config)
            }
        }
    }

    private fun tryLoadCachedRemote(context: Context, view: android.view.View, config: WallpaperConfig) {
        CoroutineScope(Dispatchers.IO).launch {
            val cacheFile = getCacheFile(context, config.remoteUrl)
            if (cacheFile.exists()) {
                val bitmap = BitmapFactory.decodeStream(FileInputStream(cacheFile))
                if (bitmap != null) {
                    val processed = processBitmap(bitmap, config)
                    val drawable = BitmapDrawable(context.resources, processed)
                    Handler(Looper.getMainLooper()).post {
                        applyDrawableToView(view, drawable, config)
                    }
                }
            }
        }
    }

    private fun cacheRemoteWallpaper(context: Context, bitmap: Bitmap, url: String) {
        val cacheDir = File(context.filesDir, PRESET_DIR)
        cacheDir.mkdirs()
        val fileName = "remote_${url.hashCode().toString(36)}.jpg"
        val cacheFile = File(cacheDir, fileName)
        try {
            FileOutputStream(cacheFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache remote wallpaper", e)
        }
    }

    private fun getCacheFile(context: Context, url: String): File {
        val cacheDir = File(context.filesDir, PRESET_DIR)
        val fileName = "remote_${url.hashCode().toString(36)}.jpg"
        return File(cacheDir, fileName)
    }

    // ==================== SLIDESHOW ====================

    private fun startSlideshow(context: Context, view: android.view.View, config: WallpaperConfig) {
        stopSlideshow()
        currentSlideshowIndex = 0

        val presets = getBuiltInPresets(context)
        val slideshowPresets = if (config.slideshowPresetIds.isNotEmpty()) {
            presets.filter { it.id in config.slideshowPresetIds }
        } else {
            presets.filter { it.type == WallpaperPreset.Type.GRADIENT }
        }

        if (slideshowPresets.isEmpty()) return

        slideshowHandler = Handler(Looper.getMainLooper())
        slideshowRunnable = object : Runnable {
            override fun run() {
                val preset = slideshowPresets[currentSlideshowIndex % slideshowPresets.size]
                val drawable = createGradientDrawable(preset)
                applyDrawableToView(view, drawable, config.copy(presetId = preset.id))
                currentSlideshowIndex++
                slideshowHandler?.postDelayed(this, config.slideshowIntervalMs.toLong())
            }
        }
        slideshowHandler?.post(slideshowRunnable!!)
    }

    fun stopSlideshow() {
        slideshowHandler?.removeCallbacks(slideshowRunnable!!)
        slideshowHandler = null
        slideshowRunnable = null
    }

    // ==================== PROCESAMIENTO DE IMÁGENES ====================

    private fun processBitmap(bitmap: Bitmap, config: WallpaperConfig): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()

        // Draw bitmap
        canvas.drawBitmap(bitmap, 0f, 0f, paint)

        // Apply dim overlay
        if (config.dimAmount > 0) {
            paint.color = Color.argb((255 * config.dimAmount).toInt(), 0, 0, 0)
            paint.style = Paint.Style.FILL
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }

        // Apply blur
        if (config.blurRadius > 0) {
            return applyBlur(result, config.blurRadius)
        }

        return result
    }

    private fun applyBlur(bitmap: Bitmap, radius: Int): Bitmap {
        val blurred = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(blurred)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.maskFilter = BlurMaskFilter(radius.toFloat(), BlurMaskFilter.Blur.NORMAL)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return blurred
    }

    // ==================== DRAWABLES ====================

    private fun createGradientDrawable(preset: WallpaperPreset): Drawable {
        return android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.fromAngle(preset.gradientAngle),
            preset.gradientColors
        ).apply {
            setDither(true)
        }
    }

    private fun createSolidDrawable(preset: WallpaperPreset): Drawable {
        return android.graphics.drawable.ColorDrawable(preset.solidColor)
    }

    private fun applyDrawableToView(view: android.view.View, drawable: Drawable, config: WallpaperConfig) {
        view.background = drawable

        // Apply dim via overlay if needed (for images)
        if (config.source == WallpaperConfig.Source.LOCAL_FILE ||
            config.source == WallpaperConfig.Source.REMOTE_URL ||
            config.source == WallpaperConfig.Source.SLIDESHOW) {

            val dimOverlay = view.findViewById<android.view.View>(R.id.wallpaper_dim_overlay)
            if (dimOverlay != null && config.dimAmount > 0) {
                dimOverlay.visibility = android.view.View.VISIBLE
                dimOverlay.setBackgroundColor(Color.argb((255 * config.dimAmount).toInt(), 0, 0, 0))
            } else if (dimOverlay != null) {
                dimOverlay.visibility = android.view.View.GONE
            }
        }
    }

    // ==================== EXPORTAR / IMPORTAR ====================

    fun exportConfig(context: Context): String {
        val config = getConfig(context)
        return config.toJson()
    }

    fun importConfig(context: Context, json: String): Boolean {
        return try {
            val config = WallpaperConfig.fromJson(json)
            saveConfig(context, config)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import config", e)
            false
        }
    }

    // ==================== HELPERS ====================

    fun saveLocalWallpaper(context: Context, bitmap: Bitmap, fileName: String): String? {
        val dir = File(context.filesDir, PRESET_DIR)
        dir.mkdirs()
        val file = File(dir, fileName)
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            return file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save local wallpaper", e)
            return null
        }
    }

    fun loadLocalWallpaper(context: Context, path: String): Bitmap? {
        return try {
            BitmapFactory.decodeStream(FileInputStream(path))
        } catch (e: Exception) {
            null
        }
    }

    fun bitmapToBase64(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    fun base64ToBitmap(base64: String): Bitmap? {
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }
}