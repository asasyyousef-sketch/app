package com.camstream.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * شهادة موقّعة ذاتياً تُنشأ داخل الهاتف (AndroidKeyStore).
 * الغرض الوحيد: المتصفح لا يسمح بالمايك إلا على https، لذلك نوفر منفذ 3421.
 */
object TlsUtil {
    private const val ALIAS = "camstream_tls_v1"

    fun createContext(): SSLContext {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val now = System.currentTimeMillis()

        val existing = ks.getCertificate(ALIAS) as? X509Certificate
        if (existing != null && existing.notAfter.time < now + 30L * 86_400_000L) {
            ks.deleteEntry(ALIAS)
        }
        if (!ks.containsAlias(ALIAS)) {
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(2048)
                .setDigests(
                    KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA1,
                    KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512
                )
                .setSignaturePaddings(
                    KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                    KeyProperties.SIGNATURE_PADDING_RSA_PSS
                )
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=CamStream"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(Date(now - 86_400_000L))
                .setCertificateNotAfter(Date(now + 800L * 86_400_000L))
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair()
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, null)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        return ctx
    }
}
