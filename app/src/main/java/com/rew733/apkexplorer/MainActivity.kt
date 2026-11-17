package com.rew733.apkexplorer

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var parserEngine: ApkParserEngine
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val pickApkLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            processSelectedUri(uri)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        parserEngine = ApkParserEngine(this)
        webView = WebView(this)
        setContentView(webView)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.loadUrl("file:///android_asset/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("if (window.handleAndroidBack) { window.handleAndroidBack(); } else { false; }") { result ->
                    if (result != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun processSelectedUri(uri: Uri) {
        sendToJs("onLoadingState", JSONObject().put("loading", true).put("message", "جاري تفكيك حزمة APK...").toString())
        mainScope.launch {
            val resultJson = withContext(Dispatchers.IO) {
                parserEngine.loadApkFromUri(uri)
            }
            sendToJs("onApkAnalyzed", resultJson)
        }
    }

    private fun sendToJs(functionName: String, jsonPayload: String) {
        val escaped = JSONObject.quote(jsonPayload)
        webView.post {
            webView.evaluateJavascript("if (window.$functionName) { window.$functionName(JSON.parse($escaped)); }", null)
        }
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun requestInstalledApps() {
            mainScope.launch {
                val appsJson = withContext(Dispatchers.IO) {
                    parserEngine.getInstalledAppsJson()
                }
                sendToJs("onInstalledAppsLoaded", appsJson)
            }
        }

        @JavascriptInterface
        fun openFilePicker() {
            runOnUiThread {
                try {
                    pickApkLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "تعذر فتح مدير الملفات: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun inspectInstalledApp(sourceDir: String) {
            sendToJs("onLoadingState", JSONObject().put("loading", true).put("message", "جاري استخراج الملفات والأكواد...").toString())
            mainScope.launch {
                val resultJson = withContext(Dispatchers.IO) {
                    parserEngine.loadApkFromPath(sourceDir)
                }
                sendToJs("onApkAnalyzed", resultJson)
            }
        }

        @JavascriptInterface
        fun readEntryCode(entryPath: String) {
            mainScope.launch {
                val content = withContext(Dispatchers.IO) {
                    parserEngine.readEntryContent(entryPath)
                }
                val payload = JSONObject()
                    .put("path", entryPath)
                    .put("content", content)
                    .toString()
                sendToJs("onEntryContentLoaded", payload)
            }
        }

        @JavascriptInterface
        fun copyToClipboard(text: String) {
            runOnUiThread {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Extracted Code", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@MainActivity, "تم نسخ الكود إلى الحافظة", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun shareCode(title: String, code: String) {
            runOnUiThread {
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, code)
                    type = "text/plain"
                }
                startActivity(Intent.createChooser(sendIntent, "مشاركة الكود عبر"))
            }
        }
    }

    override fun onDestroy() {
        mainScope.cancel()
        super.onDestroy()
    }
}
