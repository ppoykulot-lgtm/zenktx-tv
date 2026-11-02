package ph.zenktx.tv.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import ph.zenktx.tv.BuildConfig
import java.security.MessageDigest

/**
 * Build authenticity check.
 *
 * A repackaged APK almost always needs a different signing key, so comparing
 * the signing certificate against the one ZENKTX was released with catches
 * patched builds. This is tamper *evidence*, not encryption: it stops the
 * common "decompile, edit, re-sign, redistribute" path, not a determined
 * attacker who has the release keystore.
 */
object Integrity {

    data class Verdict(val ok: Boolean, val message: String = "")

    fun check(context: Context): Verdict {
        if (BuildConfig.DEBUG) return Verdict(true)

        val expected = BuildConfig.CERT_SHA256.replace(":", "").lowercase()
        if (expected.isBlank()) return Verdict(true)

        val actual = signingSha256(context) ?: return Verdict(
            false,
            "This build has no readable signature. Install ZENKTX from the official release page.",
        )

        return if (actual == expected) Verdict(true)
        else Verdict(
            false,
            "This copy of ZENKTX was modified and re-signed by someone else. " +
                "Download the official build from the ZENKTX releases page.",
        )
    }

    private fun signingSha256(context: Context): String? = runCatching {
        val pm = context.packageManager
        val pkg = context.packageName
        val bytes: ByteArray = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: return null
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray() ?: return null
        }
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
