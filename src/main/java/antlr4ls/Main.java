
package antlr4ls;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

public class Main {

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        Antlr4Server server = new Antlr4Server();
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, System.in, System.out);
        server.connect(launcher.getRemoteProxy());
        Future<Void> listen = launcher.startListening();
        listen.get();
    }
}
