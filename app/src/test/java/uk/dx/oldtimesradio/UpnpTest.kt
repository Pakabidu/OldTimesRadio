package uk.dx.oldtimesradio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class UpnpTest {
    private val tv = """<?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>Living Room TV</friendlyName>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/upnp/control/RenderingControl1</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>upnp/control/AVTransport1</controlURL>
              </service>
            </serviceList>
          </device>
        </root>"""

    /** Like a Sonos: the renderer is a device nested inside a "ZonePlayer", with URLBase set. */
    private val nested = """<?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <URLBase>http://192.168.1.40:1400/</URLBase>
          <device>
            <deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>
            <friendlyName>192.168.1.40 - Sonos One</friendlyName>
            <deviceList>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>Kitchen</friendlyName>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:2</serviceType>
                    <controlURL>/MediaRenderer/AVTransport/Control</controlURL>
                  </service>
                </serviceList>
              </device>
            </deviceList>
          </device>
        </root>"""

    @Test fun readsDescription() {
        val r = Upnp.parseDescription(tv, "http://192.168.1.20:8080/desc.xml")!!
        assertEquals("Living Room TV", r.name)
        assertEquals("http://192.168.1.20:8080/upnp/control/AVTransport1", r.avTransport)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1", r.avType)
        assertEquals("http://192.168.1.20:8080/upnp/control/RenderingControl1", r.rendering)
    }

    @Test fun nestedRendererAndUrlBase() {
        val r = Upnp.parseDescription(nested, "http://192.168.1.40:1400/xml/device_description.xml")!!
        assertEquals("Kitchen", r.name)
        assertEquals("http://192.168.1.40:1400/MediaRenderer/AVTransport/Control", r.avTransport)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:2", r.avType)
        assertNull(r.rendering)
    }

    @Test fun noTransportOrBadXml() {
        assertNull(Upnp.parseDescription(tv.replace("AVTransport", "Nothing"), "http://h/"))
        assertNull(Upnp.parseDescription("not xml", "http://h/"))
    }

    @Test fun ssdpLocation() {
        val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.20:8080/desc.xml\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        assertEquals("http://192.168.1.20:8080/desc.xml", Upnp.location(reply))
        assertNull(Upnp.location("HTTP/1.1 200 OK\r\nST: x\r\n\r\n"))
        assertTrue(Upnp.searchMessage().startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(Upnp.searchMessage().endsWith("\r\n\r\n"))
    }

    @Test fun messagesAreEscaped() {
        val url = "http://h/stream?a=1&b=2"
        val didl = Upnp.didl("Tom & Jerry's <Radio>", url, "audio/mpeg")
        assertTrue(didl.contains("<dc:title>Tom &amp; Jerry&apos;s &lt;Radio&gt;</dc:title>"))
        assertTrue(didl.contains(">http://h/stream?a=1&amp;b=2</res>"))
        val env = Upnp.envelope("urn:schemas-upnp-org:service:AVTransport:1", "SetAVTransportURI",
            listOf("InstanceID" to "0", "CurrentURI" to url, "CurrentURIMetaData" to didl))
        // The DIDL is escaped a second time inside the envelope.
        assertTrue(env.contains("<CurrentURI>http://h/stream?a=1&amp;b=2</CurrentURI>"))
        assertTrue(env.contains("<CurrentURIMetaData>&lt;DIDL-Lite"))
        assertFalse(env.contains("<CurrentURIMetaData><DIDL"))
    }

    @Test fun replies() {
        assertEquals("PLAYING", Upnp.transportState("<s:Envelope><s:Body><u:GetTransportInfoResponse><CurrentTransportState>PLAYING</CurrentTransportState></u:GetTransportInfoResponse></s:Body></s:Envelope>"))
        assertEquals("PAUSED_PLAYBACK", Upnp.transportState("<x:CurrentTransportState> PAUSED_PLAYBACK </x:CurrentTransportState>"))
        assertNull(Upnp.transportState("<nothing/>"))
        assertEquals("714 Illegal MIME-type", Upnp.fault("<UPnPError><errorCode>714</errorCode><errorDescription>Illegal MIME-type</errorDescription></UPnPError>"))
    }

    @Test fun mimeGuess() {
        assertEquals("audio/mpeg", Upnp.mime("http://65.108.124.70:8181/stream"))
        assertEquals("audio/aac", Upnp.mime("https://x/radio.aac"))
    }

    /**
     * A tiny HTTP server for the fake renderer, on a plain ServerSocket (the JDK's own
     * HTTP server isn't on the Android unit-test classpath). [handle] gets the path, the
     * SOAPACTION header and the body, and returns the status and reply.
     */
    private class FakeRenderer(val handle: (path: String, action: String?, body: String) -> Pair<Int, String>) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}"
        private val thread = Thread {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { c ->
                    val input = c.getInputStream().bufferedReader(Charsets.UTF_8)
                    val path = input.readLine()?.split(" ")?.getOrNull(1) ?: return@use
                    var length = 0; var action: String? = null
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        val (k, v) = line.split(":", limit = 2).let { it[0].trim().lowercase() to it.getOrElse(1) { "" }.trim() }
                        if (k == "content-length") length = v.toInt()
                        if (k == "soapaction") action = v.trim('"').substringAfter('#')
                    }
                    val buf = CharArray(length); var n = 0
                    while (n < length) { val r = input.read(buf, n, length - n); if (r < 0) break; n += r }
                    val (code, reply) = handle(path, action, String(buf, 0, n))
                    val bytes = reply.toByteArray(Charsets.UTF_8)
                    c.getOutputStream().apply {
                        write(("HTTP/1.1 $code X\r\nContent-Type: text/xml\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        write(bytes); flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { server.close() }
    }

    /** End to end against a fake renderer: describe, play, ask its state, mute, stop. */
    @Test fun talksToARenderer() {
        val calls = mutableListOf<String>()
        FakeRenderer { path, action, body ->
            if (path == "/desc.xml") return@FakeRenderer 200 to tv
            synchronized(calls) { calls.add(action ?: "?") }
            when (action) {
                "GetTransportInfo" -> 200 to "<s:Envelope><s:Body><u:GetTransportInfoResponse xmlns:u=\"x\"><CurrentTransportState>PLAYING</CurrentTransportState></u:GetTransportInfoResponse></s:Body></s:Envelope>"
                "SetAVTransportURI" -> if ("&lt;dc:title&gt;Radio Swiss Jazz" in body) 200 to "<ok/>"
                    else 500 to "<UPnPError><errorCode>402</errorCode><errorDescription>Invalid Args</errorDescription></UPnPError>"
                else -> 200 to "<ok/>"
            }
        }.use { fake ->
            val r = Upnp.describe("${fake.base}/desc.xml")!!
            assertEquals("Living Room TV", r.name)
            Upnp.play(r, "Radio Swiss Jazz", "https://stream.srg-ssr.ch/srgssr/rsj/mp3/128")
            assertEquals("PLAYING", Upnp.state(r))
            Upnp.mute(r, true)
            Upnp.stop(r)
            assertEquals(listOf("Stop", "SetAVTransportURI", "Play", "GetTransportInfo", "SetMute", "Stop"), synchronized(calls) { calls.toList() })
            // A renderer's fault comes back as the error message.
            val err = runCatching { Upnp.soap(r.avTransport, r.avType, "SetAVTransportURI", listOf("InstanceID" to "0")) }.exceptionOrNull()
            assertEquals("402 Invalid Args", err?.message)
        }
    }
}
