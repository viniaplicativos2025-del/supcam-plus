package pt.supcamplus

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private lateinit var content: FrameLayout
    private lateinit var live: LiveView
    private lateinit var editor: EditorView
    private lateinit var tLive: TextView
    private lateinit var tEdit: TextView

    private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) editor.load(uri)
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        window.navigationBarColor = Ui.CARD
        bindWifi()

        live = LiveView(this, CameraApi())
        editor = EditorView(this) { pick.launch("video/*") }
        content = FrameLayout(this)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(Ui.CARD)
        tLive = tab("Direto") { select(true) }
        tEdit = tab("Editor") { select(false) }
        bar.addView(tLive, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(tEdit, LinearLayout.LayoutParams(0, -2, 1f))

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        select(true)
    }

    private fun tab(label: String, onClick: () -> Unit): TextView {
        val t = Ui.text(this, label, 15f, Ui.MUTED, true)
        t.gravity = Gravity.CENTER
        t.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun select(isLive: Boolean) {
        val v: View = if (isLive) live else editor
        content.removeAllViews()
        content.addView(v, FrameLayout.LayoutParams(-1, -1))
        tLive.setTextColor(if (isLive) Ui.ACCENT else Ui.MUTED)
        tEdit.setTextColor(if (isLive) Ui.MUTED else Ui.ACCENT)
    }

    // O Android tende a largar o Wi-Fi da câmara (sem internet); força o tráfego por ele.
    private fun bindWifi() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        cm.requestNetwork(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
            }
        })
    }
}
