package com.aimediasorter.app.domain.cloud
import android.content.Context
import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class CloudMediaEncoder(private val context: Context) {
    private fun encode(bitmap: Bitmap,time: Long?=null): CloudImage {
        val out=ByteArrayOutputStream()
        if(!bitmap.compress(Bitmap.CompressFormat.JPEG,85,out)) throw CloudFailure(CloudErrorKind.RESPONSE,AnalysisCode.LOCAL_JPEG_ENCODE)
        val bytes=out.toByteArray()
        if(bytes.size>349_000 || bytes.size<4 || bytes[0]!=0xff.toByte() || bytes[1]!=0xd8.toByte() ||
            bytes[bytes.size-2]!=0xff.toByte() || bytes.last()!=0xd9.toByte())
            throw CloudFailure(CloudErrorKind.RESPONSE,AnalysisCode.LOCAL_JPEG_ENCODE)
        return CloudImage(bytes,time)
    }
    suspend fun images(uri: Uri,path: String?,isVideo: Boolean,orientation: Int,mirrored: Boolean): List<CloudImage> {
        try { return decode(uri,isVideo,orientation,mirrored) }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(known: CloudFailure) { throw known }
        catch(_: SecurityException) { throw CloudFailure(CloudErrorKind.RESPONSE,AnalysisCode.LOCAL_SOURCE_ACCESS) }
        catch(_: java.io.FileNotFoundException) { throw CloudFailure(CloudErrorKind.RESPONSE,AnalysisCode.LOCAL_SOURCE_ACCESS) }
        catch(_: Exception) { throw CloudFailure(CloudErrorKind.RESPONSE,
            if(isVideo) AnalysisCode.LOCAL_VIDEO_DECODE else AnalysisCode.LOCAL_IMAGE_DECODE) }
    }
    private suspend fun decode(uri: Uri,isVideo: Boolean,orientation: Int,mirrored: Boolean): List<CloudImage> {
        if(!isVideo) {
            val access=SelectedMediaAccess(context)
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            access.input(uri).use { BitmapFactory.decodeStream(it,null,bounds) }
            require(bounds.outWidth>0 && bounds.outHeight>0)
            var sample=1
            while(bounds.outWidth/sample>1024 || bounds.outHeight/sample>1024) sample*=2
            val bitmap=access.input(uri).use { BitmapFactory.decodeStream(it,null,
                BitmapFactory.Options().apply { inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888 }) }
                ?: throw CloudFailure(CloudErrorKind.RESPONSE,AnalysisCode.LOCAL_IMAGE_DECODE)
            val exif=runCatching { access.input(uri).use { ExifInterface(it) } }.getOrNull()
            // Preserve explicitly inspected orientation; selected EXIF supplies metadata when
            // strict original access could not inspect it. Some decoders synthesize normal EXIF.
            val rotation=if(orientation!=0) orientation else exif?.rotationDegrees ?: 0
            val flipped=mirrored || exif?.isFlipped==true
            var oriented: Bitmap?=null
            var scaled: Bitmap?=null
            return try {
                val matrix=Matrix().apply { postRotate(rotation.toFloat());if(flipped) postScale(-1f,1f) }
                val upright=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
                oriented=upright
                val ratio=minOf(1f,512f/maxOf(upright.width,upright.height))
                val bounded=Bitmap.createScaledBitmap(upright,maxOf(1,(upright.width*ratio).toInt()),maxOf(1,(upright.height*ratio).toInt()),true)
                scaled=bounded
                listOf(encode(bounded))
            } finally {
                if(scaled!=null && scaled!==oriented && scaled!==bitmap) scaled.recycle()
                if(oriented!=null && oriented!==bitmap) oriented.recycle()
                bitmap.recycle()
            }
        }
        val retriever=MediaMetadataRetriever()
        try {
            retriever.setDataSource(context,uri)
            val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            require(duration>0)
            val frames=mutableListOf<CloudImage>();val hashes=hashSetOf<String>()
            for(percent in listOf(10,50,90)) {
                currentCoroutineContext().ensureActive()
                val time=duration*percent/100
                val bitmap=retriever.getScaledFrameAtTime(time*1000,MediaMetadataRetriever.OPTION_CLOSEST,512,512) ?: continue
                try {
                    val pixels=IntArray(bitmap.width*bitmap.height)
                    bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                    val bytes=java.nio.ByteBuffer.allocate(pixels.size*4).apply { asIntBuffer().put(pixels) }.array()
                    val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    if(hashes.add(hash)) frames+=encode(bitmap,time)
                } finally { bitmap.recycle() }
            }
            require(frames.isNotEmpty())
            return frames
        } finally { retriever.release() }
    }
}
