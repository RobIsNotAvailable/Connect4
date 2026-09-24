import java.awt.Graphics2D;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.RepaintManager;

// The ghost disc: where my disc would land in the column under the mouse. The
// board is painted into an image and the centre of each hole is read back.
public class GhostTest extends Rig
{
    static JComponent board;
    static int boardRepaints;

    static void mouse(int id, int x) throws Exception
    {
        edt(() -> board.dispatchEvent(new MouseEvent(board, id, System.currentTimeMillis(),
                0, x, 300, 0, false, MouseEvent.NOBUTTON)));
    }

    static void move(int x) throws Exception
    {
        mouse(MouseEvent.MOUSE_MOVED, x);
    }

    static void leave() throws Exception
    {
        mouse(MouseEvent.MOUSE_EXITED, -1);
    }

    // The board as the player sees it, one character per hole, rows split by
    // '/': '.' empty, 'R' and 'G' discs, 'r' and 'g' ghosts, '?' anything else.
    static String holes() throws Exception
    {
        StringBuilder seen = new StringBuilder();
        edt(() ->
        {
            BufferedImage image = new BufferedImage(board.getWidth(), board.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            board.paint(g);
            g.dispose();

            int cellWidth = board.getWidth() / 7;
            int cellHeight = board.getHeight() / 6;
            for(int row = 0; row < 6; row++)
            {
                for(int col = 0; col < 7; col++)
                {
                    int rgb = image.getRGB(col * cellWidth + cellWidth / 2, row * cellHeight + cellHeight / 2);
                    int r = (rgb >> 16) & 255, gr = (rgb >> 8) & 255, b = rgb & 255;
                    char hole;
                    if(r == 84 && gr == 84 && b == 84)        hole = '.'; // UiUtil.BACKGROUND_GRAY
                    else if(r == 224 && gr == 58 && b == 58)  hole = 'R'; // UiUtil.ERROR_RED
                    else if(r == 58 && gr == 224 && b == 97)  hole = 'G'; // UiUtil.SUCCESS_GREEN
                    else if(r > gr + 30 && r < 200)           hole = 'r'; // red seen through
                    else if(gr > r + 30 && gr < 200)          hole = 'g'; // green seen through
                    else                                      hole = '?';
                    seen.append(hole);
                }
                if(row < 5)
                {
                    seen.append('/');
                }
            }
        });
        return seen.toString();
    }

    // What holes() should give: the board of the server, with 'ghost' drawn
    // at (row, col) - no ghost when row is -1.
    static String expect(String board, int row, int col, char ghost)
    {
        StringBuilder picture = new StringBuilder();
        for(int r = 0; r < 6; r++)
        {
            for(int c = 0; c < 7; c++)
            {
                char cell = board.charAt(r * 7 + c);
                if(r == row && c == col)
                {
                    picture.append(ghost);
                }
                else
                {
                    picture.append(cell == '1' ? 'R' : cell == '2' ? 'G' : '.');
                }
            }
            if(r < 5)
            {
                picture.append('/');
            }
        }
        return picture.toString();
    }

    static String noGhost(String board)
    {
        return expect(board, -1, -1, ' ');
    }

    // Every game has a board of its own: the one on screen, 703 px wide.
    static void onScreenBoard() throws Exception
    {
        board = board();
        edt(() -> board.setSize(703, 600));
    }

    static void checkHoles(String name, String expected) throws Exception
    {
        String got = holes();
        check(name, got.equals(expected), got + ", expected " + expected);
    }

    public static void main(String[] args) throws Exception
    {
        // Counts the repaints the board asks for.
        edt(() -> RepaintManager.setCurrentManager(new RepaintManager()
        {
            @Override
            public void addDirtyRegion(JComponent c, int x, int y, int w, int h)
            {
                if(c == board)
                {
                    boardRepaints++;
                }
                super.addDirtyRegion(c, x, y, w, h);
            }
        }));

        start();
        logIn("Anna");

        server("GAME_START 3 1 Bob");
        server("GAME_STATE 3 1 " + EMPTY_BOARD);
        onScreenBoard();
        checkHoles("mouse not on the board", noGhost(EMPTY_BOARD));
        move(350);
        checkHoles("empty column: at the bottom", expect(EMPTY_BOARD, 5, 3, 'r'));

        // Moving inside a column repaints nothing, changing column repaints once.
        boardRepaints = 0;
        move(310);
        move(399);
        check("same column: no repaint", boardRepaints == 0, "" + boardRepaints);
        move(400);
        check("new column: one repaint", boardRepaints == 1, "" + boardRepaints);
        checkHoles("column 4", expect(EMPTY_BOARD, 5, 4, 'r'));

        String twoDiscs = "......." + "......." + "......." + "......." + "...2..." + "...1...";
        server("GAME_STATE 3 1 " + twoDiscs);
        move(350);
        checkHoles("on top of the discs", expect(twoDiscs, 3, 3, 'r'));
        move(0);
        checkHoles("column 0, the discs untouched", expect(twoDiscs, 5, 0, 'r'));

        leave();
        checkHoles("mouse gone", noGhost(twoDiscs));
        move(701);
        checkHoles("pixels left over on the right", noGhost(twoDiscs));

        // The turn passes: no ghost. It comes back: the ghost too, one row up,
        // without moving the mouse.
        move(350);
        server("GAME_STATE 3 2 " + twoDiscs);
        checkHoles("opponent's turn", noGhost(twoDiscs));
        String threeDiscs = "......." + "......." + "......." + "...2..." + "...2..." + "...1...";
        server("GAME_STATE 3 1 " + threeDiscs);
        checkHoles("my turn again", expect(threeDiscs, 2, 3, 'r'));

        String full = "...1..." + "...2..." + "...1..." + "...2..." + "...1..." + "...2...";
        server("GAME_STATE 3 1 " + full);
        checkHoles("full column", noGhost(full));
        move(250);
        checkHoles("next to the full column", expect(full, 5, 2, 'r'));

        String oneLeft = "......." + "...2..." + "...1..." + "...2..." + "...1..." + "...2...";
        server("GAME_STATE 3 1 " + oneLeft);
        move(350);
        checkHoles("last hole, in the top row", expect(oneLeft, 0, 3, 'r'));

        server("GAME_STATE 3 0 " + oneLeft);
        checkHoles("game over", noGhost(oneLeft));

        // A board shown again, for the rematch, forgets where the mouse was
        // until it moves, and shows the new game.
        move(150);
        server("GAME_OVER 3 LOSE");
        click("Rematch");
        server("GAME_START 3 1 Bob");
        server("GAME_STATE 3 1 " + EMPTY_BOARD);
        checkHoles("rematch on screen: empty board, no ghost yet", noGhost(EMPTY_BOARD));
        move(150);
        checkHoles("rematch: the ghost is back", expect(EMPTY_BOARD, 5, 1, 'r'));

        // Another game, as player 2, on a board of its own.
        gameButton("Home");
        server("GAME_START 9 2 Carl");
        server("GAME_STATE 9 2 " + EMPTY_BOARD);
        onScreenBoard();
        checkHoles("new game on screen: no ghost yet", noGhost(EMPTY_BOARD));
        move(150);
        checkHoles("player 2: green ghost", expect(EMPTY_BOARD, 5, 1, 'g'));

        sent();
        edt(() -> board.dispatchEvent(new MouseEvent(board, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK, 150, 300, 1, false, MouseEvent.BUTTON1)));
        check("a click plays in the ghost's column", sent().equals(List.of("MOVE 9 1")));

        finish("GhostTest");
    }
}
