/*
 * Copyright 2023 CodeMaker AI Inc. All rights reserved.
 */

package ai.codemaker.jetbrains.assistant.handler

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import io.ktor.util.*
import org.cef.callback.CefCallback
import org.cef.handler.CefResourceHandler
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.nio.file.Path

class StreamResourceHandler(private val resourcePath: String, parent: Disposable) : CefResourceHandler, Disposable {

    private var input: InputStream? = null

    private var mimeType = "text/html"

    init {
        Disposer.register(parent, this)
    }

    override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
        val url = URI(request!!.url).toURL()
        val path = url.path

        input = this.javaClass.classLoader.getResourceAsStream(Path.of(resourcePath, path).toString())
        if (input == null) {
            return false
        }

        when (Path.of(path).extension) {
            "html" -> mimeType = "text/html";
            "svg" -> mimeType = "image/svg+xml";
        }

        callback!!.Continue()
        return true
    }

    override fun getResponseHeaders(response: CefResponse?, responseLength: IntRef?, redirectUrl: StringRef?) {
        responseLength!!.set(input!!.available())
        response!!.mimeType = mimeType
        response.status = 200
    }

    override fun readResponse(
        dataOut: ByteArray?,
        bytesToRead: Int,
        bytesRead: IntRef?,
        callback: CefCallback?
    ): Boolean {
        try {
            bytesRead!!.set(input!!.read(dataOut!!, 0, bytesToRead))
            if (bytesRead.get() != -1) {
                return true
            }
        } catch (e: IOException) {
            callback!!.cancel()
        }
        bytesRead!!.set(0)
        Disposer.dispose(this)
        return false
    }

    override fun cancel() {
        Disposer.dispose(this)
    }

    override fun dispose() {
        try {
            input?.close()
            input = null
        } catch (e: IOException) {
            // ignores
        }
    }
}