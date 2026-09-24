import com.lso.MainController;
import com.lso.view.OverlayPanel;

import java.awt.Component;
import java.awt.Container;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

// The real client, run without a window and without a server. Every test of
// this folder extends it and is a main() that exits with 1 if a check fails;
// run.sh compiles and runs them.
//
// - MainFrame is replaced by the stub in stub/, so nothing is shown.
// - server("...") hands a line of the server straight to the controller, on
//   the Swing thread, as the network thread would: when it returns, the line
//   has been handled. No waiting, so no test depends on timing.
// - sent() gives what the client sent since the last call.
// - The client still connects when it starts (a failed connection would put an
//   error box on screen), to a socket of ours that stays silent.
//
// The widgets are private fields, reached by name: if one is renamed the test
// stops with NoSuchFieldException, naming it.
public class Rig
{
    static final String EMPTY_BOARD = ".".repeat(42);

    static MainController controller;
    static OverlayPanel overlay;

    // The client prints every line it receives: only the checks are shown.
    private static final PrintStream report = System.out;
    private static final StringWriter outgoing = new StringWriter();
    private static Socket connection; // kept open: a closed one means "connection lost"
    private static int passed;
    private static int failed;

    interface SwingAction
    {
        void run() throws Exception;
    }

    static void start() throws Exception
    {
        // A test that stops with an exception (a button that is not there, a
        // renamed field) must end: the Swing thread would keep it alive.
        Thread.setDefaultUncaughtExceptionHandler((thread, e) ->
        {
            Throwable cause = e;
            while(cause.getCause() != null)
            {
                cause = cause.getCause();
            }
            report.println("  STOPPED by " + cause + ", after " + passed + " passed and " + failed + " failed checks");
            e.printStackTrace(report);
            System.exit(1);
        });
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));

        ServerSocket listener = new ServerSocket(Integer.getInteger("test.port"));
        edt(() -> controller = new MainController());
        connection = listener.accept();

        // The network thread sets 'out' as soon as it is connected; from then
        // on the client writes to us.
        long deadline = System.currentTimeMillis() + 5000;
        while(get(controller, "out") == null)
        {
            if(System.currentTimeMillis() > deadline)
            {
                throw new IllegalStateException("the client did not connect");
            }
            Thread.sleep(10);
        }
        edt(() -> set(controller, "out", new PrintWriter(outgoing, true)));
        overlay = (OverlayPanel) get(controller, "overlay");
    }

    // Gets past the username box, which every test meets first.
    static void logIn(String name) throws Exception
    {
        server("WELCOME");
        type(name, false);
        server("USERNAME_SET " + name);
        sent();
    }

    static void check(String name, boolean ok)
    {
        check(name, ok, null);
    }

    // 'detail' says what was there instead, when the check fails.
    static void check(String name, boolean ok, String detail)
    {
        if(ok)
        {
            passed++;
        }
        else
        {
            failed++;
            report.println("  FAIL " + name + (detail != null ? "  (got: " + detail + ")" : ""));
        }
    }

    static void finish(String test)
    {
        report.println(test + ": " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---- The two ends of the connection

    static void server(String line) throws Exception
    {
        edt(() -> invoke(controller, "handleServerMessage", line));
    }

    static List<String> sent()
    {
        List<String> lines = new ArrayList<>();
        for(String line : outgoing.toString().split("\\R"))
        {
            if(!line.isEmpty())
            {
                lines.add(line);
            }
        }
        outgoing.getBuffer().setLength(0);
        return lines;
    }

    // ---- The overlay: the boxes drawn over the window

    static boolean shown() throws Exception
    {
        boolean[] visible = new boolean[1];
        edt(() -> visible[0] = overlay.isVisible());
        return visible[0];
    }

    static String title() throws Exception
    {
        return labelText(overlay, "titleLabel");
    }

    static String message() throws Exception
    {
        return labelText(overlay, "messageLabel");
    }

    // The text in the box's text field.
    static String typed() throws Exception
    {
        String[] text = new String[1];
        edt(() -> text[0] = ((JTextField) get(overlay, "inputField")).getText());
        return text[0];
    }

    // What a box says, for the detail of a failed check.
    static String box() throws Exception
    {
        return shown() ? title() + ": " + message() : "no box";
    }

    // A click after the box has been on screen for a while, as a person
    // would: the guard against double clicks (OverlayPanel) is let go first.
    static void click(String button) throws Exception
    {
        edt(() -> set(overlay, "openedAt", System.nanoTime() - 10_000_000_000L));
        clickNow(button);
    }

    // A click right away, as the second click of a double click.
    static void clickNow(String button) throws Exception
    {
        edt(() -> findButton((JPanel) get(overlay, "buttonPanel"), button).doClick(0));
    }

    // Types into the box's text field, then presses OK or Enter.
    static void type(String text, boolean enter) throws Exception
    {
        edt(() ->
        {
            JTextField field = (JTextField) get(overlay, "inputField");
            field.setText(text);
            if(enter)
            {
                field.postActionEvent();
            }
        });
        if(!enter)
        {
            click("OK");
        }
    }

    // ---- The lobby

    static Object lobby() throws Exception
    {
        return get(controller, "lobbyPanel");
    }

    static String status() throws Exception
    {
        return labelText(lobby(), "statusLabel");
    }

    static int selected(String table) throws Exception
    {
        int[] row = new int[1];
        edt(() -> row[0] = ((JTable) get(lobby(), table)).getSelectedRow());
        return row[0];
    }

    static void select(String table, int row) throws Exception
    {
        edt(() -> ((JTable) get(lobby(), table)).setRowSelectionInterval(row, row));
    }

    static void lobbyButton(String text) throws Exception
    {
        edt(() -> findButton((Container) lobby(), text).doClick(0));
    }

    static boolean lobbyButtonEnabled(String text) throws Exception
    {
        boolean[] enabled = new boolean[1];
        edt(() -> enabled[0] = findButton((Container) lobby(), text).isEnabled());
        return enabled[0];
    }

    // ---- The game screen: one tab per game

    static JTabbedPane tabs() throws Exception
    {
        return (JTabbedPane) get(controller, "gamesTabs");
    }

    // The board of the selected tab, the one the player sees on the game screen.
    static Object gamePanel() throws Exception
    {
        Object[] panel = new Object[1];
        edt(() -> panel[0] = tabs().getSelectedComponent());
        if(panel[0] == null)
        {
            throw new IllegalStateException("no game has a tab");
        }
        return panel[0];
    }

    static JComponent board() throws Exception
    {
        return (JComponent) get(gamePanel(), "boardView");
    }

    // A button of the game screen: Home, Abandon, Col 1..7.
    static void gameButton(String text) throws Exception
    {
        edt(() -> findButton((Container) gamePanel(), text).doClick(0));
    }

    static List<String> tabTitles() throws Exception
    {
        List<String> titles = new ArrayList<>();
        edt(() ->
        {
            for(int i = 0; i < tabs().getTabCount(); i++)
            {
                titles.add(tabs().getTitleAt(i));
            }
        });
        return titles;
    }

    // A click on the tab of game 'id', as the player would.
    static void selectTab(int id) throws Exception
    {
        edt(() -> tabs().setSelectedComponent((Component) ((Map<?, ?>) get(controller, "activeGamePanels")).get(id)));
    }

    // The line over the column buttons of the board on screen.
    static String notification() throws Exception
    {
        return labelText(gamePanel(), "notificationLabel");
    }

    // The names in the bar over the board on screen: "player 1 | player 2".
    static String players() throws Exception
    {
        return labelText(gamePanel(), "player1Label") + " | " + labelText(gamePanel(), "player2Label");
    }

    // ---- Plumbing

    // Runs 'action' on the Swing thread and waits for it; on the Swing thread
    // already (a helper called by another one), it just runs it.
    static void edt(SwingAction action) throws Exception
    {
        if(SwingUtilities.isEventDispatchThread())
        {
            action.run();
            return;
        }
        SwingUtilities.invokeAndWait(() ->
        {
            try
            {
                action.run();
            }
            catch(Exception e)
            {
                throw new RuntimeException(e);
            }
        });
    }

    // A missing button stops the test: clicking nothing would pass unnoticed.
    static JButton findButton(Container root, String text)
    {
        JButton button = search(root, text);
        if(button == null)
        {
            throw new IllegalStateException("no button \"" + text + "\"");
        }
        return button;
    }

    private static JButton search(Container root, String text)
    {
        for(Component c : root.getComponents())
        {
            if(c instanceof JButton && (((JButton) c).getText().equals(text)
                                        || ((JButton) c).getText().startsWith(text + " (")))
            {
                return (JButton) c;
            }
            if(c instanceof Container)
            {
                JButton inside = search((Container) c, text);
                if(inside != null)
                {
                    return inside;
                }
            }
        }
        return null;
    }

    static Object get(Object owner, String name) throws Exception
    {
        return field(owner, name).get(owner);
    }

    static void set(Object owner, String name, Object value) throws Exception
    {
        field(owner, name).set(owner, value);
    }

    private static Field field(Object owner, String name) throws Exception
    {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static void invoke(Object owner, String method, String argument) throws Exception
    {
        Method m = owner.getClass().getDeclaredMethod(method, String.class);
        m.setAccessible(true);
        m.invoke(owner, argument);
    }

    // The text of a label as the player reads it: the overlay wraps its
    // messages in HTML so they break over lines.
    private static String labelText(Object owner, String label) throws Exception
    {
        String[] text = new String[1];
        edt(() -> text[0] = ((JLabel) get(owner, label)).getText().replaceAll("<[^>]*>", ""));
        return text[0];
    }
}
