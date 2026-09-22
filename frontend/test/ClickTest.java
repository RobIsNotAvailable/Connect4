import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.JComponent;

// Moves made with the mouse on the board, and with the column buttons (which
// the keys 1-7 press): the clicks that send a MOVE and the ones that must not.
public class ClickTest extends Rig
{
    static JComponent board;

    // A press of 'button' at x on the board; returns what the client sent.
    static List<String> pressAt(int x, int button) throws Exception
    {
        int mask = (button == MouseEvent.BUTTON1) ? InputEvent.BUTTON1_DOWN_MASK
                 : (button == MouseEvent.BUTTON3) ? InputEvent.BUTTON3_DOWN_MASK
                 : InputEvent.BUTTON2_DOWN_MASK;
        sent();
        edt(() -> board.dispatchEvent(new MouseEvent(board, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), mask, x, 300, 1, false, button)));
        return sent();
    }

    static List<String> pressAt(int x) throws Exception
    {
        return pressAt(x, MouseEvent.BUTTON1);
    }

    static List<String> columnButton(int col) throws Exception
    {
        sent();
        gameButton("Col " + (col + 1));
        return sent();
    }

    public static void main(String[] args) throws Exception
    {
        start();
        logIn("Anna");
        board = board();
        // 703 px: columns of 100 px, and 3 px left over on the right.
        edt(() -> board.setSize(703, 600));

        check("no game yet: nothing sent", pressAt(350).isEmpty());

        server("GAME_START 3 1 Bob");
        server("GAME_STATE 3 1 " + EMPTY_BOARD);

        // Every column, at its centre and at both edges.
        for(int c = 0; c < 7; c++)
        {
            List<String> move = List.of("MOVE 3 " + c);
            check("col " + c + " centre", pressAt(c * 100 + 50).equals(move));
            check("col " + c + " left edge", pressAt(c * 100).equals(move));
            check("col " + c + " right edge", pressAt(c * 100 + 99).equals(move));
        }

        check("pixels left over on the right", pressAt(700).isEmpty() && pressAt(702).isEmpty());
        check("right button", pressAt(350, MouseEvent.BUTTON3).isEmpty());
        check("middle button", pressAt(350, MouseEvent.BUTTON2).isEmpty());

        server("GAME_STATE 3 2 " + EMPTY_BOARD);
        check("opponent's turn", pressAt(350).isEmpty());

        // Column 3 full, the others free.
        String full = "...1..." + "...2..." + "...1..." + "...2..." + "...1..." + "...2...";
        server("GAME_STATE 3 1 " + full);
        check("full column", pressAt(350).isEmpty());
        check("column next to the full one", pressAt(250).equals(List.of("MOVE 3 2")));

        server("GAME_STATE 3 0 " + full);
        check("game over", pressAt(350).isEmpty());

        // Another game, as player 2: its id, its turn.
        server("GAME_OVER 3 WIN");
        click("Home");
        server("GAME_START 9 2 Carl");
        server("GAME_STATE 9 2 " + EMPTY_BOARD);
        check("player 2 in game 9", pressAt(50).equals(List.of("MOVE 9 0")));

        for(int c = 0; c < 7; c++)
        {
            check("button Col " + (c + 1), columnButton(c).equals(List.of("MOVE 9 " + c)));
        }
        server("GAME_STATE 9 1 " + EMPTY_BOARD);
        check("button on the opponent's turn", columnButton(0).isEmpty());

        // A box over the board stops the keys 1-7 too, which press these
        // buttons: the box is answered first.
        server("GAME_STATE 9 2 " + EMPTY_BOARD);
        gameButton("Abandon");
        check("a box is open", shown(), box());
        check("key behind a box: no move", columnButton(0).isEmpty());
        click("Stay");
        check("box answered: the key plays", columnButton(0).equals(List.of("MOVE 9 0")));

        finish("ClickTest");
    }
}
