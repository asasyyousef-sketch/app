package com.camstream.app

import android.os.SystemClock
import android.util.Base64
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/** ما يحتاجه السيرفر من الخدمة (حالة + أوامر تحكم + صوت التحدث). */
interface StreamController {
    fun statusJson(admin: Boolean): String
    fun command(name: String, params: Map<String, String>): Boolean
    fun talkData(buf: ByteArray, len: Int)
    fun talkEnded()
}

/** عدّاد المشاهدين مشترك بين سيرفر http وسيرفر https. */
object ViewerStats {
    private val viewers = ConcurrentHashMap<Long, String>()
    private val allTime = ConcurrentHashMap.newKeySet<String>()
    private val ids = AtomicLong()

    fun add(ip: String): Long {
        val id = ids.incrementAndGet()
        viewers[id] = ip
        allTime.add(ip)
        FrameBroker.viewers.incrementAndGet()
        return id
    }

    fun remove(id: Long) {
        if (viewers.remove(id) != null) FrameBroker.viewers.decrementAndGet()
    }

    fun count(): Int = viewers.size
    fun ips(): List<String> = viewers.values.distinct()
    fun uniqueEver(): Int = allTime.size

    fun reset() {
        viewers.clear(); allTime.clear(); FrameBroker.viewers.set(0)
    }
}

/**
 * سيرفر HTTP/HTTPS بسيط (بدون مكتبات):
 *   /          الصفحة            /stream    بث MJPEG
 *   /audio     صوت المايك        /snapshot  لقطة JPEG
 *   /status    حالة JSON         /api/...   أوامر (مشرف)
 *   /talk      WebSocket للتحدث (مشرف)
 */
class HttpStreamServer(
    private val port: Int,
    private val ssl: SSLContext?,
    private val controller: StreamController,
    private val page: ByteArray,
    private val viewerKey: () -> String,
    private val adminKey: () -> String
) {
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var running = false

    fun start() {
        val ss: ServerSocket = ssl?.serverSocketFactory?.createServerSocket() ?: ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        (ss as? SSLServerSocket)?.enabledProtocols = arrayOf("TLSv1.2")
        serverSocket = ss
        running = true
        pool.execute {
            while (running) {
                try {
                    val s = ss.accept()
                    pool.execute { handle(s) }
                } catch (e: Exception) {
                    if (!running) break
                }
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        sockets.forEach { try { it.close() } catch (_: Exception) {} }
        sockets.clear()
        pool.shutdownNow()
    }

    // ------------------------------------------------------------------
    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = ins.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    private fun parseQuery(q: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (kv in q.split('&')) {
            if (kv.isEmpty()) continue
            val i = kv.indexOf('=')
            try {
                if (i < 0) m[URLDecoder.decode(kv, "UTF-8")] = ""
                else m[URLDecoder.decode(kv.substring(0, i), "UTF-8")] =
                    URLDecoder.decode(kv.substring(i + 1), "UTF-8")
            } catch (_: Exception) {
            }
        }
        return m
    }

    private fun handle(s: Socket) {
        sockets.add(s)
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.soTimeout = 10_000
            val ins = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(ins) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val l = readLine(ins) ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            val target = requestLine.split(" ").getOrNull(1) ?: "/"
            val path = target.substringBefore('?')
            val params = parseQuery(target.substringAfter('?', ""))
            val out = s.getOutputStream()
            val ip = (s.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "?"

            val ak = adminKey()
            val isAdmin = ak.isNotEmpty() && params["akey"] == ak
            val vk = viewerKey()
            val isViewer = isAdmin || vk.isEmpty() || params["key"] == vk
            if (!isViewer) {
                send(out, "401 Unauthorized", "text/html; charset=utf-8",
                    "<meta charset=utf-8><body style='background:#000;color:#fff;font-family:sans-serif;text-align:center;padding:40px'>الرابط غير صحيح — مطلوب مفتاح الدخول</body>".toByteArray())
                return
            }

            when {
                path == "/" || path == "/index.html" ->
                    send(out, "200 OK", "text/html; charset=utf-8", page)

                path == "/snapshot" -> {
                    FrameBroker.touch()
                    val f = FrameBroker.latestFrame()
                        ?: FrameBroker.next(FrameBroker.currentSeq(), 3000)
                    if (f == null) send(out, "503 Service Unavailable", "text/plain", "no frame".toByteArray())
                    else send(out, "200 OK", "image/jpeg", FrameBroker.upright(f))
                }

                path == "/status" ->
                    send(out, "200 OK", "application/json", controller.statusJson(isAdmin).toByteArray())

                path == "/stream" -> streamMjpeg(s, out, ip)

                path == "/audio" -> streamAudio(s, out)

                path.startsWith("/api/") -> {
                    if (!isAdmin) {
                        send(out, "403 Forbidden", "application/json", "{\"ok\":false}".toByteArray())
                    } else {
                        val ok = controller.command(path.removePrefix("/api/"), params)
                        send(out, "200 OK", "application/json", "{\"ok\":$ok}".toByteArray())
                    }
                }

                path == "/talk" -> {
                    if (!isAdmin) send(out, "403 Forbidden", "text/plain", "forbidden".toByteArray())
                    else handleTalk(s, ins, out, headers)
                }

                else -> send(out, "404 Not Found", "text/plain", "not found".toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            try { s.close() } catch (_: Exception) {}
            sockets.remove(s)
        }
    }

    private fun send(out: OutputStream, status: String, type: String, body: ByteArray) {
        val h = "HTTP/1.1 $status\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        out.write(h.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    // ------------------------- بث الفيديو -----------------------------
    private fun streamMjpeg(s: Socket, out: OutputStream, ip: String) {
        val id = ViewerStats.add(ip)
        val local = ip == "127.0.0.1" || ip == "0:0:0:0:0:0:0:1"
        try {
            s.soTimeout = 0
            // مهم جداً لمنع التأخير: مخزن إرسال صغير يجعل الكتابة تنتظر الشبكة الفعلية،
            // فنتخطى الإطارات القديمة بدل أن تتكدس في ذاكرة النظام لثوانٍ أو دقائق.
            try { s.sendBufferSize = 128 * 1024 } catch (_: Exception) {}
            val head = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                    "Cache-Control: no-cache, no-store\r\n" +
                    "Pragma: no-cache\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.flush()
            var seq = 0L
            while (running && !s.isClosed) {
                val f = FrameBroker.next(seq, 5000) ?: continue
                seq = f.seq
                val ph = ("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + f.data.size +
                        "\r\nX-Rot: " + f.rot + "\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
                val pkt = ByteArray(ph.size + f.data.size + 2)
                System.arraycopy(ph, 0, pkt, 0, ph.size)
                System.arraycopy(f.data, 0, pkt, ph.size, f.data.size)
                pkt[pkt.size - 2] = 13
                pkt[pkt.size - 1] = 10
                val t0 = SystemClock.elapsedRealtime()
                out.write(pkt)
                out.flush()
                if (!local) Adaptive.report(SystemClock.elapsedRealtime() - t0)
            }
        } finally {
            ViewerStats.remove(id)
        }
    }

    // ------------------------- بث الصوت -------------------------------
    private fun streamAudio(s: Socket, out: OutputStream) {
        val q = AudioBroker.subscribe()
        try {
            s.soTimeout = 0
            try { s.sendBufferSize = 32 * 1024 } catch (_: Exception) {}
            val head = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/octet-stream\r\n" +
                    "X-Sample-Rate: ${AudioBroker.SAMPLE_RATE}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val silence = ByteArray(320)
            while (running && !s.isClosed) {
                val b = q.poll(3, TimeUnit.SECONDS) ?: silence   // صمت قصير = كشف انقطاع العميل
                out.write(b)
                out.flush()
            }
        } finally {
            AudioBroker.unsubscribe(q)
        }
    }

    // ------------------------- WebSocket: التحدث -----------------------
    private fun handleTalk(s: Socket, ins: InputStream, out: OutputStream, headers: Map<String, String>) {
        val key = headers["sec-websocket-key"] ?: return
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
            Base64.NO_WRAP
        )
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
        out.flush()
        s.soTimeout = 0
        val din = DataInputStream(ins)
        try {
            while (running) {
                val b0 = din.read()
                if (b0 < 0) break
                val b1 = din.read()
                if (b1 < 0) break
                val opcode = b0 and 0x0F
                val masked = (b1 and 0x80) != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = din.readUnsignedShort().toLong()
                else if (len == 127L) len = din.readLong()
                if (len < 0 || len > 1_000_000) break
                val mask = ByteArray(4)
                if (masked) din.readFully(mask)
                val data = ByteArray(len.toInt())
                din.readFully(data)
                if (masked) {
                    for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
                }
                if (opcode == 2) controller.talkData(data, data.size)
                else if (opcode == 8) break
                else if (opcode == 9) {           // ping -> pong
                    out.write(byteArrayOf(0x8A.toByte(), 0))
                    out.flush()
                }
            }
        } finally {
            controller.talkEnded()
        }
    }
}
