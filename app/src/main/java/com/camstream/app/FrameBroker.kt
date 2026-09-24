package com.camstream.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** إطار JPEG خام + زاوية الدوران (المتصفح يدوّره، فلا نكلّف الهاتف). */
class Frame(val seq: Long, val data: ByteArray, val rot: Int, val w: Int, val h: Int)

/** وسيط بين الكاميرا (منتج) وكل المشاهدين (مستهلكين). دائماً يعطي الأحدث ويتخطى القديم. */
object FrameBroker {
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private var last: Frame? = null
    private var seq = 0L

    val viewers = AtomicInteger(0)

    @Volatile var lastDemand = 0L
    @Volatile var width = 0
    @Volatile var height = 0
    @Volatile var fps = 0

    /** نضغط الصور فقط إذا فيه مشاهد أو التطبيق مفتوح — لتوفير البطارية. */
    fun needed(): Boolean =
        viewers.get() > 0 || System.currentTimeMillis() - lastDemand < 2000

    fun touch() {
        lastDemand = System.currentTimeMillis()
    }

    fun publish(data: ByteArray, rot: Int, w: Int, h: Int) {
        lock.withLock {
            seq++
            last = Frame(seq, data, rot, w, h)
            cond.signalAll()
        }
        width = if (rot % 180 == 0) w else h
        height = if (rot % 180 == 0) h else w
    }

    fun latestFrame(): Frame? = lock.withLock { last }

    fun currentSeq(): Long = lock.withLock { seq }

    /** ينتظر صورة أحدث من [after] (وموجودة فعلاً) لمدة أقصاها [timeoutMs]. */
    fun next(after: Long, timeoutMs: Long): Frame? = lock.withLock {
        var remaining = timeoutMs * 1_000_000L
        while ((seq == after || last == null) && remaining > 0) {
            remaining = cond.awaitNanos(remaining)
        }
        if (seq == after) null else last
    }

    fun reset() {
        lock.withLock { last = null }
        width = 0
        height = 0
        fps = 0
    }

    /** نسخة معدولة (مدورة) من الإطار — تُستخدم للقطة /snapshot فقط. */
    fun upright(f: Frame): ByteArray {
        if (f.rot == 0) return f.data
        val src = BitmapFactory.decodeByteArray(f.data, 0, f.data.size) ?: return f.data
        val m = Matrix().apply { postRotate(f.rot.toFloat()) }
        val r = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val o = ByteArrayOutputStream()
        r.compress(Bitmap.CompressFormat.JPEG, 92, o)
        src.recycle(); r.recycle()
        return o.toByteArray()
    }
}

/**
 * ضبط تلقائي لجودة الـ JPEG حسب سرعة الشبكة الفعلية:
 * إذا صار إرسال الإطار بطيئاً نخفّض الجودة فوراً (حجم أصغر = لا تأخير)،
 * وإذا الشبكة سريعة نرفعها تدريجياً.
 */
object Adaptive {
    const val MAX_Q = 88
    const val MIN_Q = 30
    @Volatile var jpegQuality = 75
    private var ema = 0.0
    private var lastAdj = 0L

    fun reset() {
        jpegQuality = 75; ema = 0.0; lastAdj = 0L
    }

    @Synchronized
    fun report(sendMs: Long) {
        ema = if (ema == 0.0) sendMs.toDouble() else ema * 0.75 + sendMs * 0.25
        val now = SystemClock.elapsedRealtime()
        if (now - lastAdj < 600) return
        lastAdj = now
        if (ema > 90) jpegQuality = (jpegQuality - 8).coerceAtLeast(MIN_Q)
        else if (ema < 35) jpegQuality = (jpegQuality + 3).coerceAtMost(MAX_Q)
    }
}
