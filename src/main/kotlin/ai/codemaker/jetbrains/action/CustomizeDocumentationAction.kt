/*
 * Copyright 2023 CodeMaker AI Inc. All rights reserved.
 */

package ai.codemaker.jetbrains.action

import ai.codemaker.jetbrains.dialog.DocumentationDialog
import ai.codemaker.jetbrains.service.CodeMakerService
import ai.codemaker.sdkv2.client.model.Modify
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

class CustomizeDocumentationAction : BaseAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val dialog = DocumentationDialog()
        if (dialog.showAndGet()) {
            generateDocumentation(e)
        }
    }

    private fun generateDocumentation(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return

        val service: CodeMakerService = project.getService(CodeMakerService::class.java)

        val editor = e.getData(CommonDataKeys.EDITOR)
        if (editor != null) {
            val documentManager = PsiDocumentManager.getInstance(project)
            val psiFile = documentManager.getPsiFile(editor.document) ?: return
            val codePath = getCodePath(psiFile, editor.caretModel.offset)
            documentManager.commitDocument(editor.document)
            service.generateDocumentation(psiFile.virtualFile, Modify.NONE, codePath)
        } else {
            val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
            service.generateDocumentation(file, Modify.NONE)
        }
    }

    open fun getCodePath(psiFile: PsiFile, offset: Int): String? {
        return null
    }
}