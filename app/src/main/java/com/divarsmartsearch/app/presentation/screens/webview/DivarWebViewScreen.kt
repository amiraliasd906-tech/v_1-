package com.divarsmartsearch.app.presentation.screens.webview

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.divarsmartsearch.app.presentation.components.LoadingState

private const val EXTRACTION_INTERVAL_MS = 3000L

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DivarWebViewScreen(
    searchId: Int,
    viewModel: DivarWebViewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val onListingsExtracted = rememberUpdatedState { json: String -> viewModel.onListingsExtracted(json) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(searchId) { viewModel.load(searchId) }
    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearStatusMessage()
        }
    }

    // Divar's pages are a single-page app: content can change without a
    // full reload, so we periodically re-run extraction rather than
    // relying only on onPageFinished. Tied to this composable's
    // lifecycle so the loop stops when the user leaves the screen.
    DisposableEffect(webViewRef) {
        val webView = webViewRef
        val handler = Handler(Looper.getMainLooper())
        var isActive = webView != null
        val runnable = object : Runnable {
            override fun run() {
                if (!isActive) return
                webView?.evaluateJavascript(JsExtractionScripts.AUTO_SCROLL_SCRIPT, null)
                webView?.evaluateJavascript(JsExtractionScripts.EXTRACTION_SCRIPT, null)
                handler.postDelayed(this, EXTRACTION_INTERVAL_MS)
            }
        }
        if (webView != null) handler.postDelayed(runnable, EXTRACTION_INTERVAL_MS)

        onDispose {
            isActive = false
            handler.removeCallbacksAndMessages(null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (state.searchName.isNotBlank()) "در حال رصد: ${state.searchName}" else "دیوار") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.isLoadingSearch) {
            LoadingState(modifier = Modifier.fillMaxSize())
            return@Scaffold
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onListingsExtracted(json: String) {
                                onListingsExtracted.value(json)
                            }
                        },
                        "AndroidBridge",
                    )

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript(JsExtractionScripts.EXTRACTION_SCRIPT, null)
                        }
                    }

                    loadUrl(state.startUrl)
                    webViewRef = this
                }
            },
        )
    }
}
