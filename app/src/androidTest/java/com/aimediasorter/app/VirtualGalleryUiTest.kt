package com.aimediasorter.app

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.aimediasorter.app.data.local.*
import com.aimediasorter.app.data.preferences.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Installed UI smoke on a temporary source; no cloud call or semantic-accuracy claim. */
@RunWith(AndroidJUnit4::class)
class VirtualGalleryUiTest {
    @Test fun virtualFolderOpensRealThumbnailAndFullScreenPreview()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val device=UiDevice.getInstance(instrumentation)
        val settings=SettingsRepository(context)
        val oldSource=settings.settingsFlow.first().sourceUri
        val root=File(context.cacheDir,"gallery-ui-"+UUID.randomUUID()).apply { mkdirs() }
        val photo=File(root,"gallery-qa.png")
        Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE);photo.outputStream().use { compress(Bitmap.CompressFormat.PNG,100,it) };recycle()
        }
        val db=AppDatabase.getInstance(context)
        val categoryId="qa-ui-"+UUID.randomUUID()
        val sessionId=UUID.randomUUID().toString()
        var id=0L;var activity: android.app.Activity?=null
        try {
            db.categoryDao().upsertCategory(CategoryEntity(categoryId,"QA Visual Category","Temporary UI fixture",0,"label",10,
                destinationSubfolder="QA"))
            db.analysisSessionDao().upsertSession(AnalysisSessionEntity(sessionId,1,2,root.path,"",1,1,0,0,0,"COMPLETED","qa"))
            id=db.mediaItemDao().upsertMediaItem(MediaItemEntity(
                uriString=Uri.fromFile(photo).toString(),filePath=photo.path,fileName=photo.name,extension="png",
                isVideo=false,mimeType="image/png",fileSizeBytes=photo.length(),width=96,height=64,durationMs=0,
                originalLastModifiedMs=photo.lastModified(),exifCaptureDateMs=null,quickFingerprint="qa",
                detectedCategoryId=categoryId,detectedCategoryName="QA Visual Category",effectiveCategoryId=categoryId,
                effectiveCategoryName="QA Visual Category",calibratedConfidence=0f,rawSimilarityScore=0f,topMargin=0f,
                secondaryCategoryName=null,analyzedAtMs=1,sourceRootUri=root.path,scanSessionId=sessionId,
                inferenceEngine="GEMINI",analysisStatus="PROPOSED"))
            db.virtualGalleryDao().insertLinks(listOf(MediaCategoryLink(id,categoryId,"EFFECTIVE"),MediaCategoryLink(id,categoryId,"PROPOSED")))
            settings.updateSourceUri(root.path)
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
            activity=instrumentation.startActivitySync(Intent().setClassName(context.packageName,
                context.packageName.removeSuffix(".debug")+".MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            device.waitForIdle()
            val folders=device.wait(Until.findObject(By.text("Virtual folders")),10_000)
                ?: device.wait(Until.findObject(By.text("Sanal klasörler")),10_000)
            requireNotNull(folders).click()
            val card=device.wait(Until.findObject(By.textContains("QA Visual Category")),10_000)
            requireNotNull(card).click()
            val thumbnail=device.wait(Until.findObject(By.descContains(photo.name)),10_000)
            assertNotNull("The gallery must expose the actual media thumbnail.",thumbnail)
            requireNotNull(thumbnail).click()
            val full=device.wait(Until.findObject(By.text("View full image")),10_000)
                ?: device.wait(Until.findObject(By.text("Görseli tam ekran aç")),10_000)
            requireNotNull(full).click()
            assertTrue(device.wait(Until.hasObject(By.descContains(photo.name)),10_000))
            assertTrue(photo.exists());assertTrue(root.listFiles()!!.all { it.name==photo.name })
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            settings.updateSourceUri(oldSource)
            if(id!=0L) db.openHelper.writableDatabase.execSQL("DELETE FROM media_items WHERE id=?",arrayOf(id))
            db.categoryDao().deleteCustomCategory(categoryId)
            db.openHelper.writableDatabase.execSQL("DELETE FROM analysis_sessions WHERE sessionId=?",arrayOf(sessionId))
            root.deleteRecursively()
        }
    }
}
