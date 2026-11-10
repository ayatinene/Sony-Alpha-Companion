package io.github.ayatinene.sonycompanion.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

object PreviewBitmap {
    fun decode(open: ()->InputStream): Bitmap? {
        val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        open().use { BitmapFactory.decodeStream(it,null,options) }
        if(options.outWidth<=0 || options.outHeight<=0) return null
        var sample=1
        while(options.outWidth.toLong()/sample*(options.outHeight/sample)>6_000_000L ||
            maxOf(options.outWidth,options.outHeight)/sample>4096) sample*=2
        options.inSampleSize=sample; options.inJustDecodeBounds=false
        val bitmap=open().use { BitmapFactory.decodeStream(it,null,options) } ?: return null
        val orientation=runCatching { open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,1) } }.getOrDefault(1)
        val matrix=Matrix().apply {
            when(orientation) {
                2 -> setScale(-1f,1f)
                3 -> setRotate(180f)
                4 -> setScale(1f,-1f)
                5 -> { setRotate(90f); postScale(-1f,1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(-90f); postScale(-1f,1f) }
                8 -> setRotate(-90f)
            }
        }
        return if(matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true).also {
            if(it!==bitmap) bitmap.recycle()
        }
    }
}
