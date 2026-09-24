package com.camstream.app

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingQueue

/** توزيع صوت المايك (PCM 16kHz mono 16bit) على المستمعين. */
object AudioBroker {
    const val SAMPLE_RATE = 16000
    private val subs = CopyOnWriteArraySet<LinkedBlockingQueue<ByteArray>>()

    fun subscribe(): LinkedBlockingQueue<ByteArray> {
        val q = LinkedBlockingQueue<ByteArray>(30)
        subs.add(q)
        return q
    }

    fun unsubscribe(q: LinkedBlockingQueue<ByteArray>) {
        subs.remove(q)
    }

    fun count(): Int = subs.size

    fun publish(b: ByteArray) {
        for (q in subs) {
            if (!q.offer(b)) {      // مستمع بطيء: نتخلص من القديم بدل التراكم
                q.clear()
                q.offer(b)
            }
        }
    }
}
