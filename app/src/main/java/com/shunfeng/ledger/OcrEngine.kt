package com.shunfeng.ledger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.googlecode.tesseract.android.TessBaseAPI

/**
 * 原生 Tesseract OCR 引擎（WebView 无法稳定联网下载 Tesseract.js 模型时的兜底方案）。
 * 训练数据 chi_sim.traineddata / eng.traineddata 从 assets 复制到 app data 目录后使用。
 */
object OcrEngine {

    private var tess: TessBaseAPI? = null
    private var tessReady = false
    private var initError: String? = null

    /** 初始化引擎（复制训练数据 + 初始化 Tesseract）；重复调用安全。 */
    suspend fun ensureInit(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (tessReady) return@withContext true
        if (initError != null) return@withContext false
        try {
            val dataDir = File(context.filesDir, "tessdata").apply { mkdirs() }
            val chiFile = File(dataDir, "chi_sim.traineddata")
            val engFile = File(dataDir, "eng.traineddata")
            if (!chiFile.exists()) copyAsset(context, "tessdata/chi_sim.traineddata", chiFile)
            if (!engFile.exists()) copyAsset(context, "tessdata/eng.traineddata", engFile)

            val api = TessBaseAPI()
            val parentPath = context.filesDir.absolutePath   // Tess 要求「data/tessdata」的父路径
            val ok = api.init(parentPath, "chi_sim+eng", TessBaseAPI.OEM_LSTM_ONLY)
            if (!ok) throw IllegalStateException("TessBaseAPI.init() 返回失败")
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO_ONLY
            tess = api
            tessReady = true
            true
        } catch (t: Throwable) {
            initError = t.message ?: t.javaClass.simpleName
            false
        }
    }

    private fun copyAsset(context: Context, assetName: String, outFile: File) {
        context.assets.open(assetName).use { inp ->
            outFile.outputStream().use { out -> inp.copyTo(out) }
        }
    }

    /** 解析 data:image/...;base64,XXXX 字符串并识别文字。 */
    suspend fun recognizeBase64(context: Context, dataUrl: String): String = withContext(Dispatchers.IO) {
        if (!ensureInit(context)) throw IllegalStateException("OCR 引擎初始化失败：${initError ?: "未知错误"}")
        val bitmap = decodeDataUrl(dataUrl) ?: throw IllegalArgumentException("无法解码图片")
        tess?.let { api ->
            api.setImage(bitmap)
            api.utF8Text.orEmpty()
        } ?: throw IllegalStateException("OCR 引擎不可用")
    }

    private fun decodeDataUrl(dataUrl: String): Bitmap? {
        val base64 = dataUrl.substringAfter(',').takeIf { it.isNotEmpty() } ?: return null
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /** 页面退出时释放。 */
    fun release() {
        tess?.apply {
            recycle()
        }
        tess = null
        tessReady = false
    }
}
