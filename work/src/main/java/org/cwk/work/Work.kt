// Created by 超悟空 on 2021/4/8.

package org.cwk.work

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 任务
 *
 * 可以启动网络请求
 * 任务流程依赖协程实现，同样遵循通过协程取消任务
 *
 * 所有方法默认在[Dispatchers.IO]中执行，
 * 可通过[execute]参数改变协程上下文，
 * 部分网络请求关联的生命周期总是在[Dispatchers.IO]中执行
 *
 * 此类为任务基础模板，需要用户在项目中根据实际的业务http通用协议实现自己的基类并进一步继承实现各个接口[Work]
 *
 * @param D 关联的[Work]实际返回的[WorkData.result]结果数据类型
 * @param T [Work]返回的结果包装类型[WorkData]
 * @param H 对[okhttp3.OkHttpClient]响应数据的中间转换类型，通常由用户根据项目实际情况自行定制实现转换，参见[onResponseConvert]
 */
abstract class Work<D, T : WorkData<D>, H> : WorkCore<D, T, H>() {

    /**
     * 日志标签
     */
    @Transient
    private val _tag = "${javaClass.simpleName}@${this.hashCode().toString(16)}"

    override suspend fun execute(
        context: CoroutineContext,
        retry: Int,
        onSendProgress: OnProgress?,
        onReceiveProgress: OnProgress?
    ): T = withContext(context + CoroutineName(_tag)) {
        logV(_tag, "work execute")
        onCreateWorkData().apply {
            try {
                onStartWork(this)
                options = onCreateOptions(retry, onSendProgress, onReceiveProgress)
                onDoWork(this)
                logV(_tag, "onSuccess")
                onSuccessful(this)
            } catch (e: Exception) {
                if (e is CancellationException) {
                    errorType = WorkErrorType.CANCEL
                    logW(_tag, "onCanceled")
                    onCanceled(this)
                } else {
                    errorType = errorType ?: WorkErrorType.OTHER
                    logW(_tag, "onFailed", e)
                    onFailed(this)
                }
            } finally {
                logV(_tag, "onFinish")
                onFinished(this)
                logV(_tag, "work finished")
            }
        }
    }

    /**
     * 任务启动前置方法
     */
    private suspend fun onStartWork(data: T) {
        if (onCheckParams()) {
            return
        }

        logD(_tag, "onParamsError")
        data.errorType = WorkErrorType.PARAMS
        data.message = onParamsError()

        throw IllegalArgumentException("params error")
    }

    /**
     * 构建请求选项参数
     */
    private suspend fun onCreateOptions(
        retry: Int,
        onSendProgress: OnProgress?,
        onReceiveProgress: OnProgress?
    ): Options = Options(
        url = url(),
        params = fillParams(),
        method = httpMethod(),
        retry = retry,
        configKey = configKey(),
        headers = headers(),
        contentType = contentType(),
        onSendProgress = onSendProgress,
        onReceiveProgress = onReceiveProgress,
    ).apply { onPostOptions(this) }

    /**
     * 此处为真正启动http请求的方法
     */
    private suspend fun onDoWork(data: T) {
        onWillRequest(data)

        try {
            logI(_tag, "request", data.options)
            workRequest(data.options!!)(_tag, data.options!!)
        } catch (e: Exception) {
            logD(_tag, "onParamsError")
            data.errorType = WorkErrorType.PARAMS
            data.message = onParamsError()

            throw e
        }.let { call ->
            logI(_tag, "real url", call.request().url)

            withContext(Dispatchers.IO + CoroutineName(_tag)) {
                onCall(data, call).use {
                    onHandleResponse(data, it)
                }
            }
        }
    }

    /**
     * 执行网络请求
     */
    private suspend fun onCall(data: T, call: Call): Response = suspendCancellableCoroutine {
        it.invokeOnCancellation {
            logD(_tag, "http cancelled")
            call.cancel()
        }

        try {
            it.resume(call.execute())
        } catch (e: IOException) {
            if (it.isCancelled) {
                return@suspendCancellableCoroutine
            }

            data.errorType = if (e.message == "timeout") {
                WorkErrorType.TIMEOUT
            } else {
                WorkErrorType.NETWORK
            }
            logD(_tag, "onNetworkError")
            data.message = onNetworkError(data)

            it.resumeWithException(e)
        }
    }

    /**
     * 处理响应结果
     */
    private suspend fun onHandleResponse(data: T, response: Response) = data.apply {
        this.response = HttpResponse(response.isSuccessful, response.code, response.headers)
        logI(_tag, "response", this.response)

        if (response.isSuccessful) {
            try {
                logV(_tag, "onResponseConvert")
                onParse(this, onResponseConvert(this, response.body!!))
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    success = false
                    errorType = WorkErrorType.PARSE
                    logD(_tag, "onParseFailed")
                    message = onParseFailed(this)
                }
                throw e
            }
        } else {
            success = false
            errorType = WorkErrorType.RESPONSE
            logD(_tag, "onNetworkRequestFailed")
            message = onNetworkRequestFailed(this)

            throw IOException("http unexpected code ${response.code}")
        }
    }

    /**
     * 解析响应体
     */
    private suspend fun onParse(data: T, body: H) = data.apply {
        logI(_tag, "body", body)

        success = onRequestResult(data, body)

        if (success) {
            logV(_tag, "onRequestSuccess")
            result = onRequestSuccess(data, body)
            logV(_tag, "onRequestSuccessMessage")
            message = onRequestSuccessMessage(data, body)
        } else {
            errorType = WorkErrorType.TASK
            logD(_tag, "onRequestFailed")
            result = onRequestFailed(data, body)
            logD(_tag, "onRequestFailedMessage")
            message = onRequestFailedMessage(data, body)
        }
    }
}