
package antlr4ls;

import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.antlr.runtime.tree.Tree;
import org.antlr.v4.Tool;
import org.antlr.v4.parse.GrammarTreeVisitor;
import org.antlr.v4.tool.ANTLRMessage;
import org.antlr.v4.tool.ANTLRToolListener;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.ast.GrammarAST;
import org.antlr.v4.tool.ast.GrammarRootAST;
import org.antlr.v4.tool.ast.RuleAST;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
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
        Tool antlr = new Tool();
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

            @Override
            public CompletableFuture<Hover> hover(HoverParams params) {
                // TODO: cache tool/grammar
                var textDocument = params.getTextDocument();
                var position = params.getPosition();
                String path = Paths.get(URI.create(textDocument.getUri())).toString();
                Tool antlr = new Tool();
                GrammarRootAST rootAST = antlr.parseGrammar(path);
                Tree tree = findNode(position, rootAST);
                // TODO: anything more useful to show?
                String text = tree == null
                    ? ""
                    : tree.toStringTree();
                Hover hover = new Hover(new MarkupContent(MarkupKind.PLAINTEXT, text));
                return CompletableFuture.completedFuture(hover);
            }

            @Override
            public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
                // TODO: cache tool/grammar
                var textDocument = params.getTextDocument();
                var position = params.getPosition();
                String path = Paths.get(URI.create(textDocument.getUri())).toString();
                Tool antlr = new Tool();
                GrammarRootAST rootAST = antlr.parseGrammar(path);
                Tree tree = findNode(position, rootAST);
                ArrayList<Location> locations = new ArrayList<>();
                if (tree == null) {
                    return CompletableFuture.completedFuture(Either.forLeft(locations));
                }
                List<GrammarAST> candidates = rootAST.getNodesWithType(tree.getType());
                for (var candidate : candidates) {
                    Tree parent = candidate.getParent();
                    if (parent instanceof RuleAST rule && rule.getRuleName().equals(tree.getText())) {
                        int character = rule.getCharPositionInLine();
                        int lnum = rule.getLine() - 1;
                        Position start = new Position(lnum, character);
                        Position end = new Position(lnum, character + tree.getText().length());
                        Range range = new Range(start, end);
                        locations.add(new Location(textDocument.getUri(), range));
                    }
                }
                return CompletableFuture.completedFuture(Either.forLeft(locations));
            }

            @Override
            public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
                // TODO: cache tool/grammar
                var textDoc = params.getTextDocument();
                var position = params.getPosition();
                boolean includeDeclaration = params.getContext().isIncludeDeclaration();
                String path = Paths.get(URI.create(textDoc.getUri())).toString();
                Tool antlr = new Tool();
                GrammarRootAST rootAST = antlr.parseGrammar(path);
                Tree tree = findNode(position, rootAST);
                if (tree instanceof RuleAST) {
                    tree = tree.getChild(0);
                }
                if (tree == null) {
                    return CompletableFuture.completedFuture(List.of());
                }
                String text = tree.getText();
                List<GrammarAST> candidates = rootAST.getNodesWithType(tree.getType());
                ArrayList<Location> locations = new ArrayList<>();
                for (var candidate : candidates) {
                    if (!candidate.getText().equals(text)) {
                        continue;
                    }
                    if (!includeDeclaration && candidate.getParent() instanceof RuleAST) {
                        continue;
                    }
                    int character = candidate.getCharPositionInLine();
                    int lnum = candidate.getLine() - 1;
                    Position start = new Position(lnum, character);
                    Position end = new Position(lnum, character + text.length());
                    Range range = new Range(start, end);
                    locations.add(new Location(textDoc.getUri(), range));
                }
                return CompletableFuture.completedFuture(locations);
            }
        };
    }

    private static boolean isAtPosition(Tree tree, Position position) {
        int line = position.getLine();
        int character = position.getCharacter();
        return tree.getLine() - 1 == line
            && tree.getCharPositionInLine() <= character
            && character <= tree.getCharPositionInLine() + tree.getText().length();
    }

    protected Tree findNode(Position position, GrammarRootAST rootAST) {
        AtomicReference<Tree> result = new AtomicReference<>();
        GrammarTreeVisitor grammarTreeVisitor = new GrammarTreeVisitor() {

            @Override
            protected void enterRule(GrammarAST tree) {
                if (isAtPosition(tree, position)) {
                    result.set(tree);
                }
                super.enterRule(tree);
            }

            @Override
            protected void enterElement(GrammarAST tree) {
                if (isAtPosition(tree, position)) {
                    result.set(tree);
                }
                super.enterElement(tree);
            }

            @Override
            protected void enterLexerRule(GrammarAST tree) {
                Tree child = tree.getChild(0);
                if (isAtPosition(child, position)) {
                    result.set(child);
                    return;
                }
                super.enterRule(tree);
            }
        };
        grammarTreeVisitor.visitGrammar(rootAST);
        return result.get();
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
        capabilities.setHoverProvider(true);
        capabilities.setDefinitionProvider(true);
        capabilities.setReferencesProvider(true);
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
