package io.heapy.kvik.probe;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.execution.RunManager;
import com.intellij.psi.*;
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter;
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticProviderKt;
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity;
import org.jetbrains.kotlin.analysis.api.session.KaSessionProvider;
import org.jetbrains.kotlin.psi.KtFile;

/** Test-only inspector, loaded into an isolated IDEA profile; never part of a user's export. */
public final class KvikReferenceProbe extends LocalInspectionTool {
    @Override public String getShortName() { return "KvikReferenceProbe"; }

    @Override public PsiElementVisitor buildVisitor(ProblemsHolder holder, boolean onTheFly) {
        return new PsiElementVisitor() {
            @Override public void visitFile(PsiFile file) {
                if (!(file instanceof KtFile kotlinFile)) return;
                if (file.getName().equals("App.kt")) {
                    for (var setting : RunManager.getInstance(file.getProject()).getAllSettings()) {
                        var configuration = setting.getConfiguration();
                        try {
                            var type = configuration.getClass();
                            holder.registerProblem(file, "KVIK_RUN_CONFIGURATION|" + setting.getName() + "|"
                                + configuration.getType().getId() + "|" + type.getMethod("getScriptPath").invoke(configuration)
                                + "|" + type.getMethod("getInterpreterPath").invoke(configuration)
                                + "|" + type.getMethod("isExecuteScriptFile").invoke(configuration)
                                + "|" + type.getMethod("isExecuteInTerminal").invoke(configuration));
                        } catch (ReflectiveOperationException failure) {
                            holder.registerProblem(file, "KVIK_RUN_CONFIGURATION_ERROR " + failure);
                        }
                    }
                }
                // Use the installed IDE's Kotlin analysis, including visibility and friend modules.
                var provider = KaSessionProvider.Companion.getInstance(file.getProject());
                var session = provider.getAnalysisSession(file);
                provider.beforeEnteringAnalysis(session, file);
                try {
                    var diagnostics = KaDiagnosticProviderKt.collectDiagnostics(session, kotlinFile,
                        KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS);
                    for (var diagnostic : diagnostics) {
                        if (diagnostic.getSeverity() == KaSeverity.ERROR)
                            holder.registerProblem(diagnostic.getPsi(), "KVIK_DIAGNOSTIC " + diagnostic.getDefaultMessage());
                    }
                } finally {
                    provider.afterLeavingAnalysis(session, file);
                }
            }

            @Override public void visitElement(PsiElement element) {
                if (!element.getClass().getName().equals("org.jetbrains.kotlin.psi.KtNameReferenceExpression")) return;
                String name = element.getText();
                if (!java.util.Set.of("missingSymbol", "String", "answer", "baseValue", "hiddenValue", "generatedValue").contains(name)) return;
                var references = PsiReferenceService.getService().getReferences(element, PsiReferenceService.Hints.NO_HINTS);
                String result = references.isEmpty() ? "NO_REFERENCES" : "RESOLVED";
                for (PsiReference reference : references) if (reference.resolve() == null) result = "UNRESOLVED";
                holder.registerProblem(element, "KVIK_REFERENCE " + name + " " + result);
            }
        };
    }
}
