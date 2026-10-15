package com.aimediasorter.app.domain.cloud
import android.content.Context
import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import com.aimediasorter.app.domain.storage.OriginalMediaAccess
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class CloudMediaEncoder(private val context: Context) {
    private fun encode(bitmap: Bitmap,time: Long?=null): CloudImage {
        val out=ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG,85,out))
        val bytes=out.toByteArray();require(bytes.size<=349_000)
        return CloudImage(bytes,time)
    }
    suspend fun images(uri: Uri,path: String?,isVideo: Boolean,orientation: Int,mirrored: Boolean): List<CloudImage> {
        if(!isVideo) {
            val access=OriginalMediaAccess(context)
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            access.input(uri).use { BitmapFactory.decodeStream(it,null,bounds) }
            require(bounds.outWidth>0 && bounds.outHeight>0)
            var sample=1
            while(bounds.outWidth/sample>1024 || bounds.outHeight/sample>1024) sample*=2
            val bitmap=access.input(uri).use { BitmapFactory.decodeStream(it,null,
                BitmapFactory.Options().apply { inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888 }) }
                ?: throw CloudFailure(CloudErrorKind.RESPONSE)
            var oriented: Bitmap?=null
            var scaled: Bitmap?=null
            return try {
                val matrix=Matrix().apply { postRotate(orientation.toFloat());if(mirrored) postScale(-1f,1f) }
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
            retriever.setDataSource(context,OriginalMediaAccess(context).originalUri(uri))
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
