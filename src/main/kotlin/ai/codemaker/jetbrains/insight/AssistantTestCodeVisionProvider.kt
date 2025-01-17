/*
 * Copyright 2024 CodeMaker AI Inc. All rights reserved.
 */

package ai.codemaker.jetbrains.insight

import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class AssistantTestCodeVisionProvider : BaseAssistantCodeVisionProvider("test", "Unit Test", "Test") {

    override val relativeOrderings = listOf<CodeVisionRelativeOrdering>(
        CodeVisionRelativeOrdering.CodeVisionRelativeOrderingAfter(
            "ai.codemaker.codevision.assistant.review"
        )
    )
}