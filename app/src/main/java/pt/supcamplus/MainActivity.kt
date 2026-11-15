package pt.supcamplus

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private lateinit var content: FrameLayout
    private lateinit var live: LiveView
    private lateinit var editor: EditorView

    private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) editor.load(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindWifi()

        live = LiveView(this, CameraApi())
        editor = EditorView(this) { pick.launch("video/*") }
        content = FrameLayout(this)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        val bLive = Button(this)
        bLive.text = "Direto"
        bLive.setOnClickListener { show(live) }
        val bEdit = Button(this)
        bEdit.text = "Editor"
        bEdit.setOnClickListener { show(editor) }
        bar.addView(bLive, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(bEdit, LinearLayout.LayoutParams(0, -2, 1f))

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        show(live)
    }

    private fun show(v: View) {
        content.removeAllViews()
        content.addView(v, FrameLayout.LayoutParams(-1, -1))
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
