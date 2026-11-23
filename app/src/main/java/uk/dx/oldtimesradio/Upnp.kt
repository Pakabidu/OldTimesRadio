package uk.dx.oldtimesradio

import org.w3c.dom.Element
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * DLNA / UPnP AV: finds media renderers on the Wi-Fi (smart TVs, Sonos, AV receivers,
 * Kodi and the like) with an SSDP search, reads each one's description, and drives its
 * AVTransport service (play a URL, stop, ask what it's doing) and RenderingControl (mute)
 * with SOAP calls. Plain java.net and the platform XML parser, no Android, so it runs in
 * JVM tests. The network calls block; call them off the main thread.
 */
object Upnp {
    /** A renderer: its name, where it described itself, and its control endpoints. */
    data class Renderer(
        val name: String,
        val location: String,
        val avTransport: String,
        val avType: String,
        val rendering: String? = null,
        val rcType: String? = null,
    )

    const val SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"
    private const val SSDP_HOST = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val TIMEOUT_MS = 4000

    // ---- Messages (pure) ----

    fun searchMessage(): String = "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: $SSDP_HOST:$SSDP_PORT\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 2\r\n" +
        "ST: $SEARCH_TARGET\r\n" +
        "USER-AGENT: Android UPnP/1.0 OldTimesRadio/1.0\r\n\r\n"

    /** The LOCATION header of an SSDP reply (where the device description lives), if any. */
    fun location(reply: String): String? = reply.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
        ?.substringAfter(':')?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }

    /**
     * Reads a device description: the renderer's friendly name and the control URLs of its
     * AVTransport and RenderingControl services (resolved against URLBase or [location]).
     * Null if it has no AVTransport, so can't play anything.
     */
    fun parseDescription(xml: String, location: String): Renderer? {
        val doc = runCatching {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = true
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            f.newDocumentBuilder().parse(xml.byteInputStream())
        }.getOrNull() ?: return null
        val root = doc.documentElement
        val base = child(root, "URLBase")?.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: location
        // The device that's the renderer (it may sit inside another device), else the first.
        val devices = elements(root, "device")
        val dev = devices.firstOrNull { child(it, "deviceType")?.textContent?.contains("MediaRenderer") == true }
            ?: devices.firstOrNull() ?: return null
        val name = child(dev, "friendlyName")?.textContent?.trim().orEmpty().ifEmpty { "Media renderer" }
        var av: Pair<String, String>? = null
        var rc: Pair<String, String>? = null
        for (s in elements(dev, "service")) {
            val type = child(s, "serviceType")?.textContent?.trim() ?: continue
            val url = child(s, "controlURL")?.textContent?.trim() ?: continue
            val abs = runCatching { URL(URL(base), url).toString() }.getOrNull() ?: continue
            if (av == null && type.contains(":service:AVTransport:")) av = abs to type
            if (rc == null && type.contains(":service:RenderingControl:")) rc = abs to type
        }
        val (avUrl, avType) = av ?: return null
        return Renderer(name, location, avUrl, avType, rc?.first, rc?.second)
    }

    /** A SOAP request for [action] on [serviceType], with its arguments in order. */
    fun envelope(serviceType: String, action: String, args: List<Pair<String, String>>): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
        "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
        "<u:$action xmlns:u=\"$serviceType\">" +
        args.joinToString("") { (k, v) -> "<$k>${esc(v)}</$k>" } +
        "</u:$action></s:Body></s:Envelope>"

    /** DIDL-Lite describing a radio stream, so the renderer can show the station's name. */
    fun didl(name: String, url: String, mime: String): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
        "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
        "<dc:title>${esc(name)}</dc:title>" +
        "<upnp:artist>${esc(Tags.APP)}</upnp:artist>" +
        "<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>" +
        "<res protocolInfo=\"http-get:*:$mime:*\">${esc(url)}</res>" +
        "</item></DIDL-Lite>"

    /** The CurrentTransportState in a GetTransportInfo reply: PLAYING, STOPPED, TRANSITIONING… */
    fun transportState(reply: String): String? =
        Regex("<(?:\\w+:)?CurrentTransportState>\\s*([A-Z_]+)\\s*</").find(reply)?.groupValues?.get(1)

    /** A SOAP fault's error, e.g. "714 Illegal MIME-type", for telling the user what went wrong. */
    fun fault(reply: String): String? {
        val code = Regex("<(?:\\w+:)?errorCode>\\s*(\\d+)").find(reply)?.groupValues?.get(1)
        val text = Regex("<(?:\\w+:)?errorDescription>([^<]*)<").find(reply)?.groupValues?.get(1)?.trim()
        return listOfNotNull(code, text?.takeIf { it.isNotEmpty() }).joinToString(" ").ifEmpty { null }
    }

    /** A best guess at the stream's audio type, from its address. */
    fun mime(url: String): String {
        val l = url.lowercase()
        return when {
            "aac" in l -> "audio/aac"
            ".ogg" in l || "opus" in l -> "audio/ogg"
            ".m3u8" in l -> "application/x-mpegURL"
            else -> "audio/mpeg"
        }
    }

    fun esc(s: String): String = buildString(s.length + 16) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&apos;")
            else -> append(ch)
        }
    }

    private fun elements(e: Element, local: String): List<Element> {
        val nl = e.getElementsByTagNameNS("*", local)
        return (0 until nl.length).map { nl.item(it) as Element }
    }

    /** A direct child element by local name (not one buried in a nested device). */
    private fun child(e: Element, local: String): Element? {
        var n = e.firstChild
        while (n != null) {
            if (n is Element && (n.localName ?: n.nodeName.substringAfter(':')) == local) return n
            n = n.nextSibling
        }
        return null
    }

    // ---- Network (blocking) ----

    /** Sends the SSDP search and reports each renderer's description address as replies come in. */
    fun search(timeoutMs: Int = TIMEOUT_MS, onLocation: (String) -> Unit) {
        DatagramSocket().use { sock ->
            sock.soTimeout = 400
            val msg = searchMessage().toByteArray()
            val to = InetAddress.getByName(SSDP_HOST)
            val buf = ByteArray(4096)
            val end = System.currentTimeMillis() + timeoutMs
            var sent = 0
            while (System.currentTimeMillis() < end) {
                // Ask three times: UDP gets lost, and some devices answer only a repeat.
                if (sent < 3 && System.currentTimeMillis() > end - timeoutMs + sent * 1000L) {
                    runCatching { sock.send(DatagramPacket(msg, msg.size, to, SSDP_PORT)) }
                    sent++
                }
                try {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    location(String(p.data, 0, p.length, Charsets.UTF_8))?.let(onLocation)
                } catch (_: SocketTimeoutException) {
                }
            }
        }
    }

    /** Fetches and reads a renderer's description. */
    fun describe(location: String): Renderer? {
        val c = URL(location).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS
        return try {
            if (c.responseCode !in 200..299) null
            else parseDescription(c.inputStream.bufferedReader().use { it.readText() }, location)
        } finally { c.disconnect() }
    }

    /** One SOAP call; returns the reply, or throws with the renderer's fault. */
    fun soap(controlUrl: String, serviceType: String, action: String, args: List<Pair<String, String>>): String {
        val c = URL(controlUrl).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS; c.readTimeout = TIMEOUT_MS
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        c.setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
        try {
            c.outputStream.use { it.write(envelope(serviceType, action, args).toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException(fault(body) ?: "HTTP $code")
            return body
        } finally { c.disconnect() }
    }

    /** Plays [url] on [r], with [name] for its display. Some renderers want stopping first. */
    fun play(r: Renderer, name: String, url: String) {
        val mime = mime(url)
        runCatching { stop(r) }
        soap(r.avTransport, r.avType, "SetAVTransportURI", listOf(
            "InstanceID" to "0", "CurrentURI" to url, "CurrentURIMetaData" to didl(name, url, mime)))
        soap(r.avTransport, r.avType, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
    }

    fun stop(r: Renderer) { soap(r.avTransport, r.avType, "Stop", listOf("InstanceID" to "0")) }

    /** What the renderer's doing: PLAYING, TRANSITIONING, STOPPED, PAUSED_PLAYBACK, NO_MEDIA_PRESENT. */
    fun state(r: Renderer): String? = transportState(soap(r.avTransport, r.avType, "GetTransportInfo", listOf("InstanceID" to "0")))

    fun mute(r: Renderer, on: Boolean) {
        val url = r.rendering ?: return
        val type = r.rcType ?: return
        soap(url, type, "SetMute", listOf("InstanceID" to "0", "Channel" to "Master", "DesiredMute" to if (on) "1" else "0"))
    }
}
