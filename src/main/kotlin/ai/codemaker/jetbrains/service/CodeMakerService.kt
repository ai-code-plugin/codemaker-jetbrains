/*
 * Copyright 2023 CodeMaker AI Inc. All rights reserved.
 */
package ai.codemaker.jetbrains.service

import ai.codemaker.jetbrains.client.ClientManager
import ai.codemaker.jetbrains.file.FileExtensions
import ai.codemaker.jetbrains.settings.AppSettingsState
import ai.codemaker.jetbrains.settings.AppSettingsState.Companion.instance
import ai.codemaker.sdkv2.client.Client
import ai.codemaker.sdkv2.client.UnauthorizedException
import ai.codemaker.sdkv2.client.model.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.util.ThrowableRunnable
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.Callable
import java.util.stream.Stream

@Service(Service.Level.PROJECT)
class CodeMakerService(private val project: Project) {

    private val maximumSourceGraphDepth = 16

    private val maximumSourceContextSize = 10

    private val logger = Logger.getInstance(CodeMakerService::class.java)

    private val clientManager = ClientManager()

    private val client: Client
        get() {
            return clientManager.getClient(instance.endpoint)
        }

    fun generateCode(path: VirtualFile?, modify: Modify, codePath: String? = null) {
        process(Mode.CODE, "Generating code", path, modify, codePath)
    }

    fun generateSourceGraphCode(path: VirtualFile?) {
        processSourceGraph(Mode.CODE, "Generating source graph code", path)
    }

    fun generateInlineCode(path: VirtualFile?, modify: Modify, codePath: String? = null) {
        process(Mode.INLINE_CODE, "Generating inline code", path, modify, codePath)
    }

    fun editCode(path: VirtualFile?, modify: Modify, codePath: String, prompt: String) {
        process(Mode.EDIT_CODE, "Editing code", path, modify, codePath, prompt)
    }

    fun generateDocumentation(path: VirtualFile?, modify: Modify, codePath: String? = null, textLanguage: LanguageCode? = null, overrideIndent: Int? = null, minimalLinesLength: Int? = null, visibility: Visibility? = null) {
        process(Mode.DOCUMENT, "Generating documentation", path, modify, codePath, null, textLanguage, overrideIndent, minimalLinesLength, visibility)
    }

    fun fixSyntax(path: VirtualFile?, modify: Modify, codePath: String? = null) {
        process(Mode.FIX_SYNTAX, "Fixing code", path, modify, codePath)
    }

    fun assistantCompletion(message: String): AssistantCompletionResponse {
        try {
            val textLanguage = AppSettingsState.instance.languageCode

            return client.assistantCompletion(createAssistantCompletionRequest(message, textLanguage))
        } catch (e: UnauthorizedException) {
            logger.error("Unauthorized request. Configure the the API Key in the Preferences > Tools > CodeMaker AI menu.", e)
            throw e
        } catch (e: Exception) {
            logger.error("Failed to process assistant completion.", e)
            throw e
        }
    }

    fun assistantCodeCompletion(message: String,path: VirtualFile?): AssistantCodeCompletionResponse {
        try {
            val textLanguage = AppSettingsState.instance.languageCode
            val model = AppSettingsState.instance.model

            val source = readFile(path!!)!!
            val language = FileExtensions.languageFromExtension(path.extension)

            val contextId = registerContext(client, language!!, source, path.path)

            val response = client.assistantCodeCompletion(
                createAssistantCodeCompletionRequest(message, textLanguage, language, source, contextId, model)
            )

            if (response.output.source.isNotEmpty()) {
                writeFile(path, response.output.source)
            }

            return response
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: UnauthorizedException) {
            logger.error("Unauthorized request. Configure the the API Key in the Preferences > Tools > CodeMaker AI menu.", e)
            throw e
        } catch (e: Exception) {
            logger.error("Failed to process assistant completion.", e)
            throw e
        }
    }

    fun assistantSpeech(message: String): AssistantSpeechResponse {
        try {
            return client.assistantSpeech(createAssistantSpeechRequest(message))
        } catch (e: UnauthorizedException) {
            logger.error("Unauthorized request. Configure the the API Key in the Preferences > Tools > CodeMaker AI menu.", e)
            throw e
        } catch (e: Exception) {
            logger.error("Failed to process assistant completion.", e)
            throw e
        }
    }

    fun assistantSpeechStream(message: String): Iterator<AssistantSpeechResponse> {
        try {
            return client.assistantSpeechStream(createAssistantSpeechRequest(message))
        } catch (e: UnauthorizedException) {
            logger.error("Unauthorized request. Configure the the API Key in the Preferences > Tools > CodeMaker AI menu.", e)
            throw e
        } catch (e: Exception) {
            logger.error("Failed to process assistant completion.", e)
            throw e
        }
    }

    fun assistantFeedback(sessionId: String, messageId: String, vote: String): RegisterAssistantFeedbackResponse {
        try {
            return client.registerAssistantFeedback(createRegisterAssistantFeedbackRequest(sessionId, messageId, vote))
        } catch (e: UnauthorizedException) {
            logger.error("Unauthorized request. Configure the the API Key in the Preferences > Tools > CodeMaker AI menu.", e)
            throw e
        } catch (e: Exception) {
            logger.error("Failed to process assistant completion.", e)
            throw e
        }
    }

    fun completion(path: VirtualFile, offset: Int, isMultilineAutocompletion: Boolean): String {
        try {
            val model = AppSettingsState.instance.model

            val source = readFile(path) ?: return ""
            val language = FileExtensions.languageFromExtension(path.extension)

            val contextId = registerContext(client, language!!, source, path.path)

            val response = client.completion(createCompletionRequest(language, source, offset, isMultilineAutocompletion, contextId, model))

            return response.output.source;
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: UnauthorizedException) {
            logger.error("Unauthorized request. Configure the the API Key in the Preferences > Tools > CodeMaker AI menu.", e)
            throw e
        } catch (e: Exception) {
            logger.error("Failed to complete code in file.", e)
            return ""
        }
    }

    fun predict(path: VirtualFile?) {
        runInBackground("Predictive generation") {
            try {
                walkFiles(path) { file: VirtualFile ->
                    if (file.isDirectory) {
                        return@walkFiles true
                    }

                    try {
                        predictFile(client, file)
                        return@walkFiles true
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error("Failed to generate code in file.", e)
                        return@walkFiles false
                    }
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to generate code in file.", e)
            }
        }
    }

    fun listModels(): List<Model> {
        val resp = client.listModels(createListModelsRequest())
        return resp.models
    }

    private fun process(mode: Mode, title: String, path: VirtualFile?, modify: Modify, codePath: String?, prompt: String? = null, textLanguage: LanguageCode? = null, overrideIndent: Int? = null, minimalLinesLength: Int? = null, visibility: Visibility? = null) {
        runInBackground(title) {
            try {
                walkFiles(path) { file: VirtualFile ->
                    if (file.isDirectory) {
                        return@walkFiles true
                    }

                    try {
                        processFile(client, file, mode, modify, codePath, prompt, textLanguage, overrideIndent, minimalLinesLength, visibility)
                        return@walkFiles true
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error("Failed to process $mode in file.", e)
                        return@walkFiles false
                    }
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to process $mode task.", e)
            }
        }
    }

    private fun processSourceGraph(mode: Mode, title: String, path: VirtualFile?) {
        runInBackground(title) {
            try {
                walkFiles(path) { file: VirtualFile ->
                    if (file.isDirectory) {
                        return@walkFiles true
                    }

                    try {
                        processSourceGraphFile(client, file, mode)
                        return@walkFiles true
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error("Failed to process $mode in file.", e)
                        return@walkFiles false
                    }
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to process $mode task.", e)
            }
        }
    }

    private fun runInBackground(title: String, runnable: Runnable) {
        val task = object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Processing"
                indicator.isIndeterminate = false
                indicator.fraction = 0.0
                runnable.run()
                indicator.fraction = 1.0
            }
        }.setCancelText("Stop Generating")
        ProgressManager.getInstance().run(task)
    }

    @Throws(InterruptedException::class)
    private fun process(client: Client, mode: Mode, language: Language, source: String, path: String, modify: Modify, codePath: String?, prompt: String?, contextId: String?, model: String?, textLanguage: LanguageCode?, overrideIndent: Int?, minimalLinesLength: Int?, visibility: Visibility?): String {
        val response = client.process(createProcessRequest(mode, language, source, path, modify, codePath, prompt, contextId, model, textLanguage, overrideIndent, minimalLinesLength, visibility))
        return response.output.source
    }

    @Throws(InterruptedException::class)
    private fun predictiveProcess(client: Client, language: Language, source: String, contextId: String?, model: String?) {
        client.predict(createPredictRequest(language, source, contextId, model))
    }

    private fun walkFiles(path: VirtualFile?, iterator: ContentIterator) {
        VfsUtilCore.iterateChildrenRecursively(
                path!!,
                ::filterFile,
                iterator
        )
    }

    private fun filterFile(file: VirtualFile): Boolean {
        return file.isDirectory || FileExtensions.isSupported(file.extension)
    }

    private fun predictFile(client: Client, file: VirtualFile) {
        try {
            val model = AppSettingsState.instance.model

            val source = readFile(file) ?: return
            val language = FileExtensions.languageFromExtension(file.extension)

            val contextId = registerContext(client, language!!, source, file.path)

            predictiveProcess(client, language, source, contextId, model)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: UnauthorizedException) {
            logger.error("Unauthorized request. Configure the the API Key in the Preferences > Tools > CodeMaker AI menu.", e)
            throw e
        } catch (e: Exception) {
            logger.error("Failed to process file.", e)
        }
    }

    private fun processFile(client: Client, file: VirtualFile, mode: Mode, modify: Modify, codePath: String? = null, prompt: String? = null, textLanguage: LanguageCode? = null, overrideIndent: Int? = null, minimalLinesLength: Int?, visibility: Visibility?) {
        try {
            val model = AppSettingsState.instance.model

            val source = readFile(file) ?: return
            val language = FileExtensions.languageFromExtension(file.extension)

            val contextId = registerContext(client, mode, language!!, source, file.path)

            val output = process(client, mode, language, source, file.path, modify, codePath, prompt, contextId, model, textLanguage, overrideIndent, minimalLinesLength, visibility)

            writeFile(file, output)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: UnauthorizedException) {
            logger.error("Unauthorized request. Configure the the API Key in the Preferences > Tools > CodeMaker AI menu.", e)
            throw e
        } catch (e: Exception) {
            logger.error("Failed to process file.", e)
        }
    }

    private fun processSourceGraphFile(client: Client, file: VirtualFile, mode: Mode, depth: Int = 0) {
        try {
            val model = AppSettingsState.instance.model

            val language = FileExtensions.languageFromExtension(file.extension)
            val source = readFile(file) ?: return

            if (depth < maximumSourceGraphDepth) {
                val response = discoverContext(client, language!!, source, file.path)
                if (response.isRequiresProcessing) {
                    val paths = resolveContextPaths(response, file.path)
                    paths.forEach {
                        val dependantFile = VirtualFileManager.getInstance().findFileByNioPath(it) ?: return@forEach
                        processSourceGraphFile(client, dependantFile, Mode.CODE, depth + 1)
                    }
                }
            }

            val contextId = registerContext(client, mode, language!!, source, file.path)
            val output = process(
                client,
                mode,
                language,
                source,
                file.path,
                Modify.NONE,
                null,
                null,
                contextId,
                model,
                null,
                null,
                null,
                null
            )
            writeFile(file, output)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: UnauthorizedException) {
            logger.error("Unauthorized request. Configure the the API Key in the Preferences > Tools > CodeMaker AI menu.", e)
            throw e
        } catch (e: Exception) {
            logger.error("Failed to process file.", e)
        }
    }

    private fun registerContext(client: Client, mode: Mode, language: Language, source: String, path: String): String? {
        if (!isExtendedContextSupported(mode)) {
            return null
        }

        return registerContext(client, language, source, path)
    }

    private fun registerContext(client: Client, language: Language, source: String, path: String): String? {
        try {
            if (!AppSettingsState.instance.extendedSourceContextEnabled) {
                return null
            }

            val sourceContexts = resolveContextWithDepth(client, language, source, path, AppSettingsState.instance.extendedSourceContextDepth)

            val createContextResponse = client.createContext(CreateContextRequest())
            val contextId = createContextResponse.id

            client.registerContext(RegisterContextRequest(contextId, sourceContexts))
            return contextId
        } catch (e: Exception) {
            logger.warn("Failed to process file context.", e)
            return null
        }
    }

    private fun discoverContext(client: Client, language: Language, source: String, path: String): DiscoverContextResponse {
        return client.discoverContext(DiscoverContextRequest(Context(language, Input(source), path)))
    }

    private fun resolveContextPaths(discoverContextResponse: DiscoverContextResponse, path: String): List<Path> {
        val paths = discoverContextResponse.requiredContexts.map {
            Path.of(path).parent.resolve(it.path).normalize()
        }

        return paths.filter {
            Files.exists(it)
        }
    }

    private fun discoverContextPaths(client: Client, language: Language, source: String, path: String): List<Path> {
        val discoverContextResponse = discoverContext(client, language, source, path)
        return resolveContextPaths(discoverContextResponse, path)
    }

    private fun resolveContextWithDepth(client: Client, language: Language, source: String, path: String, maximumDepth: Int): List<Context> {
        val resolvedSourceContexts = ArrayList<Path>()

        val queue = LinkedList<Path>()
        queue.addAll(discoverContextPaths(client, language, source, path))
        var depth = 1
        var count = queue.size

        while (!queue.isEmpty() && resolvedSourceContexts.size < maximumSourceContextSize) {
            val child = queue.removeFirst()
            resolvedSourceContexts.add(child)

            if (depth + 1 <= maximumDepth) {
                queue.addAll(discoverContextPaths(client, language, source, child.toString()))
            }

            if (--count == 0) {
                count = queue.size
                depth++
            }
        }

        return resolvedSourceContexts.map {
            val file = VirtualFileManager.getInstance().findFileByNioPath(it) ?: return@map null
            val contextSource = readFile(file) ?: return@map null
            return@map Context(
                    language,
                    Input(contextSource),
                    it.toString(),
            )
        }.filterNotNull()
    }

    private fun readFile(file: VirtualFile): String? {
        return ReadAction.nonBlocking(Callable<String> {
            val documentManager = PsiDocumentManager.getInstance(project)
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@Callable null
            val document = documentManager.getDocument(psiFile) ?: return@Callable null
            return@Callable document.text
        }).executeSynchronously()
    }

    private fun writeFile(file: VirtualFile, output: String) {
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.writeCommandAction(project)
                    .run(ThrowableRunnable<java.lang.RuntimeException> {
                        val documentManager = PsiDocumentManager.getInstance(project)
                        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@ThrowableRunnable
                        val document = documentManager.getDocument(psiFile) ?: return@ThrowableRunnable

                        document.setText(output)
                        documentManager.commitDocument(document)
                    })
        }
    }

    private fun createProcessRequest(mode: Mode, language: Language, source: String, path: String, modify: Modify, codePath: String? = null, prompt: String? = null, contextId: String? = null, model: String ? = null, textLanguage: LanguageCode?, overrideIndent: Int? = null, minimalLinesLength: Int? = null, visibility: Visibility? = null): ProcessRequest {
        return ProcessRequest(
                mode,
                language,
                Input(source),
                path,
                Options(modify, codePath, prompt, true, false, contextId, model, overrideIndent, minimalLinesLength, visibility, textLanguage)
        )
    }

    private fun createPredictRequest(language: Language, source: String, contextId: String?, model: String?): PredictRequest {
        return PredictRequest(
                language,
                Input(source),
                Options(null, null, null, false, false, contextId, model, null, null, null, null)
        )
    }

    private fun createCompletionRequest(language: Language, source: String, offset: Int, isMultilineAutocompletion: Boolean, contextId: String?, model: String?): CompletionRequest {
        return CompletionRequest(
                language,
                Input(source),
                Options(null, "@$offset", null, false, isMultilineAutocompletion, contextId, model, null, null, null, null)
        )
    }

    private fun createAssistantCompletionRequest(message: String, textLanguage: LanguageCode?): AssistantCompletionRequest {
        return AssistantCompletionRequest(
            message,
            Options(null, null, null, false, false, null, null, null, null, null, textLanguage)
        )
    }

    private fun createAssistantCodeCompletionRequest(message: String, textLanguage: LanguageCode?, language: Language, source: String, contextId: String?, model: String?): AssistantCodeCompletionRequest {
        return AssistantCodeCompletionRequest(
                message,
                language,
                Input(source),
                Options(null, null, null, false, false, contextId, model, null, null, null,  textLanguage)
        )
    }

    private fun createAssistantSpeechRequest(message: String): AssistantSpeechRequest {
        return AssistantSpeechRequest(message)
    }

    private fun createRegisterAssistantFeedbackRequest(sessionId: String, messageId: String, vote: String): RegisterAssistantFeedbackRequest {
        return RegisterAssistantFeedbackRequest(sessionId, messageId, Vote.valueOf(vote))
    }

    private fun createListModelsRequest(): ListModelsRequest? {
        return ListModelsRequest()
    }

    private fun isExtendedContextSupported(mode: Mode): Boolean {
        return mode == Mode.CODE
                || mode == Mode.EDIT_CODE
                || mode == Mode.INLINE_CODE
    }
}