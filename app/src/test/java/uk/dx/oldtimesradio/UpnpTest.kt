package uk.dx.oldtimesradio

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

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

    /** End to end against a fake renderer: describe, play, ask its state, mute, stop. */
    @Test fun talksToARenderer() {
        val calls = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        fun reply(ex: com.sun.net.httpserver.HttpExchange, code: Int, body: String) {
            val b = body.toByteArray(); ex.sendResponseHeaders(code, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.createContext("/desc.xml") { reply(it, 200, tv) }
        server.createContext("/upnp/control/") { ex ->
            val action = ex.requestHeaders.getFirst("SOAPACTION").trim('"').substringAfter('#')
            val body = ex.requestBody.bufferedReader().readText()
            synchronized(calls) { calls += action }
            when (action) {
                "GetTransportInfo" -> reply(ex, 200, "<s:Envelope><s:Body><u:GetTransportInfoResponse xmlns:u=\"x\"><CurrentTransportState>PLAYING</CurrentTransportState></u:GetTransportInfoResponse></s:Body></s:Envelope>")
                "SetAVTransportURI" -> if ("&lt;dc:title&gt;Radio Swiss Jazz" in body) reply(ex, 200, "<ok/>")
                    else reply(ex, 500, "<UPnPError><errorCode>402</errorCode><errorDescription>Invalid Args</errorDescription></UPnPError>")
                else -> reply(ex, 200, "<ok/>")
            }
        }
        server.start()
        try {
            val r = Upnp.describe("$base/desc.xml")!!
            assertEquals("Living Room TV", r.name)
            Upnp.play(r, "Radio Swiss Jazz", "https://stream.srg-ssr.ch/srgssr/rsj/mp3/128")
            assertEquals("PLAYING", Upnp.state(r))
            Upnp.mute(r, true)
            Upnp.stop(r)
            assertEquals(listOf("Stop", "SetAVTransportURI", "Play", "GetTransportInfo", "SetMute", "Stop"), calls)
            // A renderer's fault comes back as the error message.
            val err = runCatching { Upnp.soap(r.avTransport, r.avType, "SetAVTransportURI", listOf("InstanceID" to "0")) }.exceptionOrNull()
            assertEquals("402 Invalid Args", err?.message)
        } finally { server.stop(0) }
    }
}
