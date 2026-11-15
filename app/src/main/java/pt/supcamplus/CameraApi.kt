package pt.supcamplus

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL

// Cliente do protocolo HTTP "Config.cgi" das câmaras MStar (descoberto no APK da SupCam Pro).
class CameraApi(val ip: String = "192.168.1.1") {

    data class Opt(val value: String, val label: String)
    data class Menu(val id: String, val title: String, val opts: List<Opt>, val cmd: String? = null)

    companion object {
        // MODE0..MODE7, pela ordem: vídeo, câmara lenta, loop, time-lapse, foto, foto auto, contínuo, temporizada.
        val modeFiles = listOf(
            "user_menus_mode_normal_rec", "user_menus_mode_slowly_rec",
            "user_menus_mode_loop_rec", "user_menus_mode_timelapse_rec",
            "user_menus_mode_normal_pic", "user_menus_mode_auto_pic",
            "user_menus_mode_continue_pic", "user_menus_mode_timing_pic"
        )
        const val GENERAL_FILE = "user_menus_setting"

        // Desligar o Wi-Fi da câmara cortaria a ligação.
        private val skipIds = setOf("Camera.Menu.WiFi")

        private val titleOverride = mapOf(
            "Camera.Menu.MovieSin" to "Rodar gravação 180°"
        )
    }

    private val base = "http://$ip"
    private val menuDir = "$base/customer/UI/release/assets/default/raw/menu/"
    private val strRe = Regex("<string\\s+name=\"([^\"]+)\"\\s*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
    private val langRe = Regex("<language\\s+name=\"([^\"]+)\"\\s*>([^<]*)</language>")
    private var strCache: Map<String, String>? = null

    fun httpGet(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 4000
            c.readTimeout = 6000
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    // action = get | set | del. Devolve null se a câmara não respondeu.
    fun cgiRaw(action: String, property: String, value: String? = null): String? {
        val q = StringBuilder("action=").append(action).append("&property=").append(property)
        if (value != null) q.append("&value=").append(value)
        return httpGet("$base/cgi-bin/Config.cgi?$q")
    }

    fun cgi(action: String, property: String, value: String? = null): Map<String, String>? {
        val body = cgiRaw(action, property, value) ?: return null
        return parse(body)
    }

    private fun parse(body: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (line in body.split('\n')) {
            val l = line.trim()
            val i = l.indexOf('=')
            if (i > 0) m[l.substring(0, i).trim()] = l.substring(i + 1).trim()
        }
        return m
    }

    fun liveUrl(av: String?): String {
        val id = if (av.isNullOrBlank()) "4" else av
        return "rtsp://$ip/liveRTSP/av$id"
    }

    // Textos traduzidos da própria câmara (pt_PT, com en_US como alternativa).
    private fun strings(): Map<String, String> {
        strCache?.let { return it }
        val out = HashMap<String, String>()
        val t = httpGet("$base/customer/UI/release/assets/default/raw/strings/strings")
        if (t != null) {
            for (m in strRe.findAll(t)) {
                val langs = langRe.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2].trim() }
                val v = langs["pt_PT"] ?: langs["en_US"]
                if (v != null) out[m.groupValues[1]] = v
            }
        }
        if (out.isNotEmpty()) strCache = out
        return out
    }

    // Lê um ficheiro de menus da câmara (XML <setting><menu id=.. strid=..><menu value=.. strid=../>...).
    fun loadMenus(file: String): List<Menu> {
        val raw = httpGet(menuDir + file) ?: return emptyList()
        val str = strings()
        val out = ArrayList<Menu>()
        var id: String? = null
        var type = ""
        var cmd: String? = null
        var title = ""
        var opts = ArrayList<Opt>()
        try {
            val p = Xml.newPullParser()
            p.setInput(StringReader(raw))
            var depth = 0
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    depth++
                    if (p.name == "menu" && depth == 2) {
                        id = p.getAttributeValue(null, "id")
                        type = p.getAttributeValue(null, "type") ?: ""
                        cmd = p.getAttributeValue(null, "command")
                        val sid = p.getAttributeValue(null, "strid") ?: id ?: ""
                        title = str[sid] ?: sid
                        opts = ArrayList()
                    } else if (p.name == "menu" && depth == 3) {
                        val v = p.getAttributeValue(null, "value")
                        if (v != null) {
                            val sid = p.getAttributeValue(null, "strid") ?: v
                            opts.add(Opt(v, str[sid] ?: sid))
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if (p.name == "menu" && depth == 2) {
                        val i = id
                        if (i != null && i.startsWith("Camera.Menu.") && i !in skipIds &&
                            (type == "items" || type == "boolean") && opts.isNotEmpty()
                        ) {
                            out.add(Menu(i, titleOverride[i] ?: title, opts, cmd))
                        }
                    }
                    depth--
                }
                ev = p.next()
            }
        } catch (e: Exception) {
            // devolve o que já foi lido
        }
        return out
    }

    // Texto bruto de tudo o que a câmara devolve, para depuração.
    fun diagnostics(): String {
        val sb = StringBuilder()
        fun sec(t: String, v: String?) {
            sb.append("##### ").append(t).append('\n').append(v ?: "(sem resposta)").append("\n\n")
        }
        for (p in listOf("Camera.Preview.*", "Camera.Menu.*", "Camera.Menu.UIMode")) {
            sec("get $p", httpGet("$base/cgi-bin/Config.cgi?action=get&property=$p"))
        }
        sec("cammenu.xml", httpGet("$base/cammenu.xml"))
        for (f in listOf(GENERAL_FILE) + modeFiles + "user_modes_setting") sec(f, httpGet(menuDir + f))
        return sb.toString()
    }
}
