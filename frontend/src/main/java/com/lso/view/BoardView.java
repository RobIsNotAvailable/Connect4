package com.lso.view;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntConsumer;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import com.lso.GameSession;

// The board of a game: the discs, and the "ghost" of the disc the player would
// drop in the column under the mouse. A click in a column is a move there.
class BoardView extends JPanel
{
    private GameSession session;
    // The column under the mouse, -1 when the mouse is not on the board.
    private int hoverCol = -1;

    BoardView(IntConsumer onColumn)
    {
        setOpaque(false);

        MouseAdapter mouse = new MouseAdapter()
        {
            // Pressed and not clicked: Swing drops the click if the mouse
            // moves even by a pixel between press and release.
            @Override
            public void mousePressed(MouseEvent e)
            {
                int col = columnAt(e.getX());
                if (col >= 0 && SwingUtilities.isLeftMouseButton(e))
                {
                    onColumn.accept(col);
                }
            }

            @Override
            public void mouseMoved(MouseEvent e)
            {
                setHover(columnAt(e.getX()));
            }

            @Override
            public void mouseExited(MouseEvent e)
            {
                setHover(-1);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    // A board shown again (a rematch) does not hear the mouse leave it: it
    // forgets where the mouse was, and the ghost comes back as soon as the
    // mouse moves on the board.
    void setSession(GameSession session)
    {
        this.session = session;
        setHover(-1);
    }

    // Drawn again only when the column changes, not at every pixel.
    private void setHover(int col)
    {
        if (col != hoverCol)
        {
            hoverCol = col;
            repaint();
        }
    }

    // The column under the point x: each one is a strip as wide as a
    // seventh of the board, as in paintComponent. -1 for the few pixels
    // left over on the right.
    private int columnAt(int x)
    {
        int col = x / (getWidth() / GameSession.COLUMNS);
        return col < GameSession.COLUMNS ? col : -1;
    }

    @Override
    protected void paintComponent(Graphics g)
    {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int cellWidth = getWidth() / GameSession.COLUMNS;
        int cellHeight = getHeight() / GameSession.ROWS;
        int diameter = Math.min(cellWidth, cellHeight) - 10;

        // The ghost: where my disc would land in the column under the
        // mouse. Only where I could play, so it goes away by itself when
        // the turn passes, the column fills up or the game is over.
        int ghostRow = (hoverCol >= 0 && session.canPlay(hoverCol)) ? session.landingRow(hoverCol) : -1;

        for (int row = 0; row < GameSession.ROWS; row++)
        {
            for (int col = 0; col < GameSession.COLUMNS; col++)
            {
                int x = col * cellWidth + (cellWidth - diameter) / 2;
                int y = row * cellHeight + (cellHeight - diameter) / 2;

                char cell = session.cell(row, col);
                g2d.setColor(cell == '.' ? UiUtil.BACKGROUND_GRAY : UiUtil.playerColor(cell - '0'));
                g2d.fillOval(x, y, diameter, diameter);

                // See-through, on top of the empty hole.
                if (row == ghostRow && col == hoverCol)
                {
                    g2d.setColor(UiUtil.withAlpha(UiUtil.playerColor(session.getMyPlayer()), 90));
                    g2d.fillOval(x, y, diameter, diameter);
                }

                g2d.setColor(UiUtil.ACCENT);
                g2d.drawOval(x, y, diameter, diameter);
            }
        }
    }
}
