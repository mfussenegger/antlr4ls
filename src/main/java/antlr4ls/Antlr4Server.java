
package antlr4ls;

import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import org.antlr.v4.Tool;
import org.antlr.v4.tool.ANTLRMessage;
import org.antlr.v4.tool.ANTLRToolListener;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.ast.GrammarRootAST;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

public class Antlr4Server implements LanguageServer, LanguageClientAware {

    private LanguageClient client;

    private static Diagnostic newDiagnostic(ANTLRMessage msg, DiagnosticSeverity severity) {
        int lnum = msg.line - 1;
        Position start = new Position(lnum, msg.charPosition);
        Position end;
        Object[] args = msg.getArgs();
        if (args.length > 0) {
            String text = args[0].toString();
            end = new Position(lnum, msg.charPosition + text.length());
        } else {
            end = start;
        }
        Range range = new Range(start, end);
        String message = msg.getMessageTemplate(true).render();
        return new Diagnostic(range, message, severity, "antlr4");
    }

    private void lintFile(String uri) {
        // trigger in didChange with debounce, asyncCompute?
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        String path = Paths.get(URI.create(uri)).toString();
        Tool antlr = new Tool(); // TODO: one tool instance?
        antlr.addListener(new ANTLRToolListener() {

            @Override
            public void info(String msg) {
            }

            @Override
            public void error(ANTLRMessage msg) {
                diagnostics.add(newDiagnostic(msg, DiagnosticSeverity.Error));
            }

            @Override
            public void warning(ANTLRMessage msg) {
                diagnostics.add(newDiagnostic(msg, DiagnosticSeverity.Warning));
            }
        });
        try {
            GrammarRootAST grammarAST = antlr.parseGrammar(path);
            Grammar grammar = antlr.createGrammar(grammarAST);
            grammar.fileName = path;
            antlr.process(grammar, false);
        } catch (Exception ex) {
        }
        var diagnosticsParams = new PublishDiagnosticsParams(uri, diagnostics);
        client.publishDiagnostics(diagnosticsParams);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return new TextDocumentService() {

            @Override
            public void didChange(DidChangeTextDocumentParams params) {
            }

            @Override
            public void didClose(DidCloseTextDocumentParams params) {
            }

            @Override
            public void didOpen(DidOpenTextDocumentParams params) {
                if (client == null) {
                    return;
                }
                lintFile(params.getTextDocument().getUri());
            }

            @Override
            public void didSave(DidSaveTextDocumentParams params) {
                if (client == null) {
                    return;
                }
                lintFile(params.getTextDocument().getUri());
            }
        };
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return new WorkspaceService() {

            @Override
            public void didChangeConfiguration(DidChangeConfigurationParams params) {
            }

            @Override
            public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
            }
        };
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams arg0) {
        var capabilities = new ServerCapabilities();
        var serverInfo = new ServerInfo("antlr4ls", "0.1.0");
        var result = new InitializeResult(capabilities, serverInfo);
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public void exit() {
        System.exit(0);
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
    }
}
