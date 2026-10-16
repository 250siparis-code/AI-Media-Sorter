package com.aimediasorter.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aimediasorter.app.domain.cloud.CloudMediaEncoder
import com.aimediasorter.app.domain.cloud.SelectedMediaAccess
import com.aimediasorter.app.domain.storage.OriginalMediaAccess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real Android MediaProvider permission/redaction contract, isolated public fixture only.
 * The test APK owns the newly created fixture; the app receives only a selected read grant.
 * All fixture rows are deleted on cleanup. No personal archive or Google API is accessed.
 */
@RunWith(AndroidJUnit4::class)
class PlatformPhotoAccessTest {
    @Test fun platformMediaStoreRejectsUnredactedOriginalButPermitsSelectedJpegPixels()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val target=instrumentation.targetContext
        val control=instrumentation.context
        assumeTrue(target.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)!=PackageManager.PERMISSION_GRANTED)
        val uri=Uri.parse("content://com.aimediasorter.app.qa.documents/control")
        val token=UUID.randomUUID().toString()
        control.startActivity(Intent().setClassName(control.packageName,FixtureGrantActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("qa.token",token))
        try {
            val deadline=SystemClock.elapsedRealtime()+10000
            while(runCatching { target.contentResolver.call(uri,"qa.state",null,null)?.getString("token")==token }.getOrDefault(false).not()) {
                check(SystemClock.elapsedRealtime()<deadline)
                Thread.sleep(50)
            }
            val result=target.contentResolver.call(uri,"qa.publishPhotograph",null,null)!!
            val selected=Uri.parse(result.getString("uri")!!)
            var originalDenied=false
            try { OriginalMediaAccess(target).input(selected).close() }
            catch(_: UnsupportedOperationException) { originalDenied=true }
            catch(_: SecurityException) { originalDenied=true }
            assertTrue("Android must reject unredacted original access without location permission.",originalDenied)
            val access=SelectedMediaAccess(target)
            val first=access.fingerprint(selected)
            assertTrue("A redacted analysis fingerprint cannot stand in for the original SHA-256.",first!=result.getString("hash"))
            val images=CloudMediaEncoder(target).images(selected,null,false,0,false)
            assertEquals(1,images.size)
            val bytes=images.single().bytes
            val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
            assertNotNull(bitmap)
            try { assertTrue(maxOf(bitmap.width,bitmap.height)<=512) } finally { bitmap.recycle() }
            assertEquals(first,access.fingerprint(selected))
            assertNull(images.single().timestampMs)
        } finally { target.contentResolver.call(uri,"qa.cleanup",null,null) }
    }
}
