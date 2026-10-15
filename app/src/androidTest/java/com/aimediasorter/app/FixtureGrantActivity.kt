package com.aimediasorter.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract

/** Runs in the test APK's UID so it can grant access to documents that it owns. */
class FixtureGrantActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = "com.aimediasorter.app.debug"
        try {
            check(packageManager.checkSignatures(packageName, target) == PackageManager.SIGNATURE_MATCH)
            val token = requireNotNull(intent.getStringExtra("qa.token"))
            require(token.length <= 128)
            val authority = "com.aimediasorter.app.qa.documents"
            val control = Uri.parse("content://" + authority + "/control")
            contentResolver.call(control, "qa.reset", null, Bundle().apply { putString("token", token) })
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            for (root in listOf("source", "destination")) {
                grantUriPermission(target, DocumentsContract.buildTreeDocumentUri(authority, root),
                    flags or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }
            grantUriPermission(target, control, flags)
        } finally {
            finish()
        }
    }
}
