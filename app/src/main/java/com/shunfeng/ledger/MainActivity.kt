package com.shunfeng.ledger

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var rootContainer: FrameLayout
    private var splashOverlay: View? = null
    private var overlayStartMs = 0L
    private var webViewReady = false
    private var statusBarPx = 0
    private var navBarPx = 0

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserRequest = 1001
    private val wechatImportRequest = 1002

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1) 不使用系统 SplashScreen API，避免出现"仅logo"→"logo+文字"两个页面。
        //    Activity 主题 windowBackground 已设为 #FBF9F3，首帧即为暖色底，无白屏。
        //    下方 Splash Overlay（logo+文字）在 setContentView 前立即添加，直接显示完整启动页。

        configureSystemBars()

        // 2) 根容器：FrameLayout，下层 WebView + 上层 SplashOverlay
        rootContainer = FrameLayout(this)

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#FBF9F3"))
        }
        rootContainer.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 3) Splash Overlay：遮住 WebView 加载期的 1s 空白
        //    使用原生 View 渲染 Logo + App 名 + 副标题，无需联网、无白屏
        //    最短显示 900ms，保证用户能完整看到 Logo + App 名（系统 SplashScreen 仅显示 Logo）
        overlayStartMs = System.currentTimeMillis()
        splashOverlay = layoutInflater.inflate(R.layout.splash_overlay, rootContainer, false)
        rootContainer.addView(splashOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        setContentView(rootContainer)

        // 监听系统栏 inset，把实际高度注入网页 CSS 变量 --sat/--sab
        ViewCompat.setOnApplyWindowInsetsListener(webView) { _, insets ->
            statusBarPx = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            navBarPx = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            // 兜底：部分 MIUI 版本 inset 为 0，回退到系统资源尺寸
            if (statusBarPx == 0) statusBarPx = sysDimen("status_bar_height")
            if (navBarPx == 0) navBarPx = sysDimen("navigation_bar_height")
            applyInsetsToWeb()
            WindowInsetsCompat.CONSUMED
        }

        // ========== 关键修复：WebView 与 Tesseract.js 的兼容性 ==========
        with(webView.settings) {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            domStorageEnabled = true                  // localStorage support
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true        // 允许本地文件互相引用（训练数据缓存）
            allowUniversalAccessFromFileURLs = true   // 允许 file:// 上下文跨域访问 CDN（Android 10+ 默认关闭）
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE   // 允许 HTTPS 页面加载 HTTP 资源（兼容 CDN 非 HTTPS 分支）
            userAgentString = userAgentString + " ShunfengLedger/1.0"
        }

        // 允许第三方 cookie（CDN 与 WebView 本地上下文不同域）
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = false

            // 页面加载完成后再次注入，确保 JS 函数已就绪
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // 标记 WebView 内容已加载 → 系统 SplashScreen 允许退场
                webViewReady = true
                // 注入应用版本号（运行时读取 PackageInfo，与 Gradle 配置保持一致）
                val versionName = runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrDefault("") ?: ""
                val versionJson = JSONObject.quote(versionName)
                view.evaluateJavascript("window.__appVersion = $versionJson;", null)
                applyInsetsToWeb()
                // 兜底：冷启动时由外部 json Intent 拉起，此时 WebView 才刚加载完，
                // 需要把启动 Intent 交给前端处理（onNewIntent 仅在前台运行时触发）
                handleIncomingFile(intent)
                // 处理来自桌面小组件的快捷跳转
                handleWidgetAction(intent)
                // 隐藏 Splash Overlay（优雅淡出 220ms，不卡顿）
                dismissSplashOverlay()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                try {
                    startActivityForResult(fileChooserParams.createIntent(), fileChooserRequest)
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    return false
                }
                return true
            }
        }

        // ========== 原生桥（同步 + OCR） ==========
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun exportData(json: String, filename: String) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    } else {
                        runOnUiThread { Toast.makeText(this@MainActivity, "导出失败：无法写入下载目录", Toast.LENGTH_SHORT).show() }
                    }
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "导出失败：${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }

            /** 前端传入 dataUrl（data:image/png;base64,XXXX），回调 window.__ocrCallback(requestId, ok, textOrError)。 */
            @JavascriptInterface
            fun ocrRecognize(requestId: String, dataUrl: String) {
                CoroutineScope(Dispatchers.Main).launch {
                    runCatching {
                        // 先推进度：15% 表示开始初始化引擎
                        eval("window.__ocrProgress && window.__ocrProgress('$requestId', 0.15, '初始化识别引擎…')")
                        val text = OcrEngine.recognizeBase64(this@MainActivity, dataUrl)
                        val escaped = JSONObject.quote(text)
                        eval("window.__ocrCallback && window.__ocrCallback('$requestId', true, $escaped)")
                    }.getOrElse { t ->
                        val escapedMsg = JSONObject.quote(t.message ?: t.javaClass.simpleName)
                        eval("window.__ocrCallback && window.__ocrCallback('$requestId', false, $escapedMsg)")
                    }
                }
            }

            /** 前端调用：唤起微信主界面。
             *  现代微信不再注册 ACTION_GET_CONTENT 给第三方，setPackage 后无法 resolveActivity，
             *  会回退到系统文件管理器（与「从文件导入数据」重复，没有意义）。
             *  新流程：直接打开微信主界面 → 用户在聊天中点开 json → 「其他应用打开」→ 选本应用
             *  → 系统以 ACTION_VIEW/SEND + content Uri 回调 MainActivity（见 onNewIntent）。 */
            @JavascriptInterface
            fun pickFileFromWechat() {
                runOnUiThread {
                    try {
                        val launch = packageManager.getLaunchIntentForPackage("com.tencent.mm")
                        if (launch != null) {
                            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(launch)
                            Toast.makeText(
                                this@MainActivity,
                                "已打开微信，请在聊天中找到 json 文件 → 长按 → 「其他应用打开」→ 选择顺风账本",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "未安装微信，请改用「从文件导入数据」",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (e: Exception) {
                        val escaped = JSONObject.quote(e.message ?: e.javaClass.simpleName)
                        eval("window.__onWechatImportResult && window.__onWechatImportResult(false, $escaped)")
                    }
                }
            }

            /** 前端调用：分享截图。
             *  @param dataUrl  形如 "data:image/jpeg;base64,/9j/4AAQ..."
             *  @param filename 期望的文件名（如 shunfeng-ledger-20260804.jpg）
             *  在 IO 线程解码 base64 → 写入 cacheDir/share/ → 通过 FileProvider 唤起系统分享面板。
             *  分享面板会自动列出微信好友、朋友圈、保存到相册等系统支持的目标。 */
            @JavascriptInterface
            fun shareImage(dataUrl: String, filename: String) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // 提取 base64 部分：去掉 "data:image/jpeg;base64," 前缀
                        val base64Data = if (dataUrl.contains(",")) {
                            dataUrl.substring(dataUrl.indexOf(",") + 1)
                        } else {
                            dataUrl
                        }
                        val bytes = Base64.decode(base64Data, Base64.NO_WRAP)

                        // 写入 cacheDir/share/<filename>
                        val shareDir = File(cacheDir, "share").apply { if (!exists()) mkdirs() }
                        val safeName = if (filename.isBlank()) "share.jpg" else filename
                        val file = File(shareDir, safeName)
                        FileOutputStream(file).use { it.write(bytes) }

                        // 通过 FileProvider 拿到可共享的 content:// URI
                        val authority = "${packageName}.fileprovider"
                        val uri = FileProvider.getUriForFile(this@MainActivity, authority, file)

                        // 构造分享 Intent
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_TITLE, "顺风账本截图")
                            // 允许接收方读取该 URI
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }

                        // 唤起系统分享面板（Chooser），自动列出微信好友/朋友圈/保存到相册等
                        val chooser = Intent.createChooser(shareIntent, "分享截图到…").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(chooser)
                    } catch (e: Exception) {
                        val msg = e.message ?: e.javaClass.simpleName
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "分享失败：$msg", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            /** 前端调用：同步订单数据到原生 SharedPreferences，供桌面小组件读取。
             *  每次增删改订单后，前端 saveDB() 会调用此方法推送最新订单 JSON。
             *  原生收到后立即刷新桌面 Widget，实现秒级更新。 */
            @JavascriptInterface
            fun syncOrders(json: String) {
                try {
                    val prefs = getSharedPreferences("widget_data", Context.MODE_PRIVATE)
                    prefs.edit().putString("orders_json", json).apply()
                    // 立即刷新桌面小组件
                    TodayWidgetProvider.notifyWidgetChanged(this@MainActivity)
                } catch (e: Exception) {
                    Log.e("MainActivity", "syncOrders error", e)
                }
            }
        }, "Android")

        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/index.html")
        } else {
            webView.restoreState(savedInstanceState)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** 接收来自微信/QQ「其他应用打开」或系统分享面板的 json 文件。
     *  launchMode=singleTop：App 已在前台时由 onNewIntent 回调，
     *  冷启动时由 onCreate 的 intent 触发（这里也要兜底处理）。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingFile(intent)
        handleWidgetAction(intent)
    }

    /** 从 Intent（ACTION_VIEW / ACTION_SEND）中提取文件并转发给前端。
     *  图片 → base64 dataUrl → window.__onSharedImage 回调（自动 OCR 识别录入）
     *  JSON → 原文 → window.__onWechatImportResult 回调（数据导入） */
    private fun handleIncomingFile(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return

        // 取 Uri：SEND 走 EXTRA_STREAM，VIEW 走 getData()
        val uri = if (action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        } else {
            intent.data
        } ?: return

        // 判断 MIME 类型：优先用 Intent 自带 type，其次用 contentResolver 查询
        val mimeType = intent.type ?: contentResolver.getType(uri) ?: ""

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                // 等页面 ready：冷启动时 WebView 可能尚未加载完，给一个短延迟
                Thread.sleep(400)

                if (mimeType.startsWith("image/")) {
                    // ===== 图片分享 → OCR 识别录入 =====
                    val dataUrl = readImageAsDataUrl(uri, mimeType)
                    val escaped = JSONObject.quote(dataUrl)
                    eval("window.__onSharedImage && window.__onSharedImage($escaped)")
                } else {
                    // ===== JSON 文件 → 数据导入 =====
                    val json = contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: throw Exception("无法读取文件")
                    val escaped = JSONObject.quote(json)
                    eval("window.__onWechatImportResult && window.__onWechatImportResult(true, $escaped)")
                }
            }.getOrElse { t ->
                val escapedMsg = JSONObject.quote(t.message ?: t.javaClass.simpleName)
                if (mimeType.startsWith("image/")) {
                    eval("window.__onSharedImageError && window.__onSharedImageError($escapedMsg)")
                } else {
                    eval("window.__onWechatImportResult && window.__onWechatImportResult(false, $escapedMsg)")
                }
            }
        }
    }

    /** 处理来自桌面小组件的快捷跳转。
     *  Widget 点击后通过 EXTRA_QUICK_ACTION 传递目标操作：
     *  - OPEN_ADD → 直接打开「记一笔」弹窗
     *  - OPEN_STATS → 切换到统计 Tab
     *  - OPEN_CALENDAR → 切换到日历 Tab
     *  - OPEN_RECORDS（默认）→ 切换到流水 Tab */
    private fun handleWidgetAction(intent: Intent?) {
        if (intent == null) return
        val action = intent.getStringExtra("quick_action") ?: return
        // 等页面 ready 后执行对应 JS
        webView.postDelayed({
            when (action) {
                "com.shunfeng.ledger.OPEN_ADD" -> {
                    eval("openModal && openModal();")
                }
                "com.shunfeng.ledger.OPEN_STATS" -> {
                    eval("document.querySelector('.tab[data-tab=stats]')?.click();")
                }
                "com.shunfeng.ledger.OPEN_CALENDAR" -> {
                    eval("document.querySelector('.tab[data-tab=calendar]')?.click();")
                }
                else -> {
                    eval("document.querySelector('.tab[data-tab=records]')?.click();")
                }
            }
        }, 600)
        // 清除 action 防止重复触发
        intent.removeExtra("quick_action")
    }

    /** 读取图片 Uri 并转为 base64 dataUrl。
     *  大图自动压缩到最大边 1920px、质量 85%，避免 base64 过大导致 WebView evaluateJavascript 超限。 */
    private fun readImageAsDataUrl(uri: Uri, mimeType: String): String {
        val input = contentResolver.openInputStream(uri)
            ?: throw Exception("无法读取图片")
        val rawBytes = input.use { it.readBytes() }

        // 解码尺寸
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts)
        val w = opts.outWidth.takeIf { it > 0 } ?: return "data:$mimeType;base64," + Base64.encodeToString(rawBytes, Base64.NO_WRAP)
        val h = opts.outHeight

        // 计算采样率：最大边限制 1920px
        val maxEdge = 1920
        var sample = 1
        while (w / (sample * 2) >= maxEdge || h / (sample * 2) >= maxEdge) sample *= 2

        // 解码（采样后的 bitmap）
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
            ?: throw Exception("图片解码失败")

        // 如果采样后仍然超过 maxEdge，进一步缩放
        val finalBmp = if (bmp.width > maxEdge || bmp.height > maxEdge) {
            val scale = maxEdge.toFloat() / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1),
                true
            ).also { if (it != bmp) bmp.recycle() }
        } else bmp

        // 压缩为 JPEG（统一格式，体积更小）
        val baos = java.io.ByteArrayOutputStream()
        finalBmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        finalBmp.recycle()
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$b64"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == fileChooserRequest) {
            val results: Array<Uri>? = if (resultCode == RESULT_OK && data != null) {
                val clip = data.clipData
                if (clip != null) {
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                } else {
                    data.data?.let { arrayOf(it) }
                }
            } else {
                null
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
            return
        }
        // 从微信导入：读取选中文件内容并回调 JS
        if (requestCode == wechatImportRequest) {
            if (resultCode == RESULT_OK && data?.data != null) {
                val uri = data.data!!
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        val json = contentResolver.openInputStream(uri)?.use {
                            it.readBytes().toString(Charsets.UTF_8)
                        } ?: throw Exception("无法读取文件")
                        val escaped = JSONObject.quote(json)
                        eval("window.__onWechatImportResult && window.__onWechatImportResult(true, $escaped)")
                    }.getOrElse { t ->
                        val escapedMsg = JSONObject.quote(t.message ?: t.javaClass.simpleName)
                        eval("window.__onWechatImportResult && window.__onWechatImportResult(false, $escapedMsg)")
                    }
                }
            } else {
                eval("window.__onWechatImportResult && window.__onWechatImportResult(false, '已取消')")
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        OcrEngine.release()
        super.onDestroy()
    }

    /** 关闭 Splash Overlay：
     *  - 保证 Overlay 至少显示 900ms，避免用户只看到 Logo 没看到 App 名就退走；
     *  - 不足 900ms 的，补等剩余时间再执行淡出（220ms） → 从根容器移除释放。 */
    private fun dismissSplashOverlay() {
        val overlay = splashOverlay ?: return
        val alreadyShown = System.currentTimeMillis() - overlayStartMs
        val minShow = 900L
        val delay = (minShow - alreadyShown).coerceAtLeast(0L)
        try {
            overlay.postDelayed({
                runCatching {
                    overlay.animate()
                        .alpha(0f)
                        .setDuration(220L)
                        .withEndAction {
                            runCatching {
                                if (overlay.parent != null) {
                                    rootContainer.removeView(overlay)
                                }
                                splashOverlay = null
                            }
                        }
                        .start()
                }
            }, delay)
        } catch (_: Throwable) {
            runCatching {
                if (overlay.parent != null) rootContainer.removeView(overlay)
                splashOverlay = null
            }
        }
    }

    /** 把状态栏/导航栏高度（换算为 CSS px）注入到网页。 */
    private fun applyInsetsToWeb() {
        val density = resources.displayMetrics.density
        val topCss = (statusBarPx / density).toInt()
        val botCss = (navBarPx / density).toInt()
        webView.post {
            webView.evaluateJavascript(
                "window.__applyInsets && window.__applyInsets($topCss, $botCss);",
                null
            )
        }
    }

    private fun eval(js: String) {
        webView.post { webView.evaluateJavascript(js, null) }
    }

    /** 读取系统维度资源（dimen）作为 inset 兜底，兼容所有 MIUI 版本。 */
    private fun sysDimen(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun configureSystemBars() {
        // 真正的沉浸式：内容绘制到状态栏/导航栏之下，栏背景透明，白底主题色透出
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        // 白底上使用深色系统栏图标
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true
        // 让系统栏图标在深色内容上仍可见（MIUI/Android 15 兼容）
        controller.show(WindowInsetsCompat.Type.statusBars())
        controller.show(WindowInsetsCompat.Type.navigationBars())
    }
}
