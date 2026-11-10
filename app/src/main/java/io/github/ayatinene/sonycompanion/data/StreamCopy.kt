package io.github.ayatinene.sonycompanion.data

import java.io.InputStream
import java.io.OutputStream

object StreamCopy {
    fun copy(input:InputStream,output:OutputStream,expected:Long,
        checkActive:()->Unit = {}, progress:(Long,Long)->Unit = {_,_->}):Long {
        val buffer=ByteArray(64*1024);var copied=0L
        while(true) {
            checkActive()
            val n=input.read(buffer)
            if(n<0)break
            if(n==0)continue
            output.write(buffer,0,n);copied+=n;progress(copied,expected)
        }
        if(copied<=0 || (expected>0 && expected!=copied)) throw java.io.EOFException("下载长度不符：$copied / $expected")
        return copied
    }
}
