
package antlr4ls;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.junit.jupiter.api.Test;

public class Antlr4ServerTest {

    static class TestClient implements LanguageClient {

        List<PublishDiagnosticsParams> diagnosticsParams = new ArrayList<>();

        @Override
        public void telemetryEvent(Object object) {
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
            diagnosticsParams.add(diagnostics);
        }

        @Override
        public void showMessage(MessageParams messageParams) {
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
            throw new UnsupportedOperationException("showMessageRequest NYI");
        }

        @Override
        public void logMessage(MessageParams message) {
        }
    }

    @Test
    public void test_reports_unreferenced_rules_as_errors() throws Exception {
        Antlr4Server server = new Antlr4Server();
        TestClient client = new TestClient();
        server.connect(client);

        CompletableFuture<InitializeResult> initialize = server.initialize(new InitializeParams());
        assertThat(initialize).succeedsWithin(1, TimeUnit.SECONDS);

        TextDocumentService textDocumentService = server.getTextDocumentService();
        URL resource = Antlr4ServerTest.class.getClassLoader().getResource("WrongRefSample.g4");
        String uri = resource.toString();
        textDocumentService.didSave(new DidSaveTextDocumentParams(new TextDocumentIdentifier(uri)));

        assertThat(client.diagnosticsParams).hasSize(1);
        List<Diagnostic> diagnostics = client.diagnosticsParams.get(0).getDiagnostics();
        assertThat(diagnostics).hasSize(1);
    }
}
