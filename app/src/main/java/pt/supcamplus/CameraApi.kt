package pt.supcamplus

import java.net.HttpURLConnection
import java.net.URL

// Cliente do protocolo HTTP "Config.cgi" das câmaras MStar (descoberto no APK da SupCam Pro).
class CameraApi(val ip: String = "192.168.1.1") {

    data class Opt(val value: String, val label: String)
    data class Menu(val id: String, val title: String, val opts: List<Opt>)

    private val base = "http://$ip"
    private val menuDir = "$base/customer/UI/release/assets/default/raw/menu/"
    private val menuFiles = listOf(
        "user_menus_setting",
        "user_menus_mode_normal_rec", "user_menus_mode_slowly_rec",
        "user_menus_mode_loop_rec", "user_menus_mode_timelapse_rec",
        "user_menus_mode_normal_pic", "user_menus_mode_auto_pic",
        "user_menus_mode_continue_pic", "user_menus_mode_timing_pic"
    )

    private val menuRe = Regex("<menu\\b([^>]*)>(.*?)</menu>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val tagRe = Regex("<(\\w+)\\b([^>]*?)/?>")
    private val attrRe = Regex("(\\w+)\\s*=\\s*\"([^\"]*)\"")

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
    fun cgi(action: String, property: String, value: String? = null): Map<String, String>? {
        val q = StringBuilder("action=").append(action).append("&property=").append(property)
        if (value != null) q.append("&value=").append(value)
        val body = httpGet("$base/cgi-bin/Config.cgi?$q") ?: return null
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
        for (f in menuFiles + "user_modes_setting") sec(f, httpGet(menuDir + f))
        sec("strings", httpGet("$base/customer/UI/release/assets/default/raw/strings/strings")?.take(20000))
        return sb.toString()
    }

    private fun attrs(s: String): Map<String, String> =
        attrRe.findAll(s).associate { it.groupValues[1] to it.groupValues[2] }

    private fun loadStrings(): Map<String, String> {
        val t = httpGet("$base/customer/UI/release/assets/default/raw/strings/strings") ?: return emptyMap()
        val m = HashMap<String, String>()
        Regex("name\\s*=\\s*\"([^\"]+)\"[^>]*>([^<]*)<").findAll(t)
            .forEach { m[it.groupValues[1]] = it.groupValues[2].trim() }
        if (m.isEmpty()) {
            t.lineSequence().forEach { l ->
                val i = l.indexOf('=')
                if (i > 0) m[l.substring(0, i).trim()] = l.substring(i + 1).trim().trim('"')
            }
        }
        return m
    }

    // Os menus vêm da própria câmara, por isso funcionam para qualquer modelo MStar.
    fun loadMenus(): List<Menu> {
        val str = loadStrings()
        val out = LinkedHashMap<String, Menu>()
        for (f in menuFiles) {
            val xml = httpGet(menuDir + f) ?: continue
            for (m in menuRe.findAll(xml)) {
                val a = attrs(m.groupValues[1])
                val id = a["command"] ?: continue
                if (out.containsKey(id)) continue
                val opts = ArrayList<Opt>()
                for (t in tagRe.findAll(m.groupValues[2])) {
                    if (t.groupValues[1].equals("sel", true)) continue
                    val ia = attrs(t.groupValues[2])
                    val v = ia["value"] ?: ia["id"] ?: continue
                    val sid = ia["strid"] ?: ia["title"] ?: v
                    opts.add(Opt(v, str[sid] ?: sid))
                }
                val sid = a["strid"] ?: id
                out[id] = Menu(id, str[sid] ?: sid, opts)
            }
        }
        return out.values.toList()
    }
}
