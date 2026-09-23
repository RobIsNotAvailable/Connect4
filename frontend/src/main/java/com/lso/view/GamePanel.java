package com.lso.view;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import com.lso.GameSession;
import com.lso.MainController;

public class GamePanel extends JPanel
{
    // How long a notification stays on screen.
    public static final int NOTIFICATION_MILLIS = 6000;

    private MainController controller;
    private JLabel player1Label;
    private JLabel player2Label;
    private BoardView boardView;
    // The game on screen. All the state (board, turn, names) is in it; the
    // panel only draws it and turns the clicks into messages.
    private GameSession session;
    private JButton homeBtn;
    private JButton abandonBtn;
    private JLabel awayLabel;
    private JLabel notificationLabel;
    private javax.swing.Timer notificationTimer;

    public GamePanel(MainController controller)
    {
        this.controller = controller;
        setLayout(new BorderLayout(0, 20));
        setOpaque(false);

        // Player 1 on the left, player 2 on the right, each with the colour
        // of their discs. The right one has the icon after the name so the
        // two sides mirror each other.
        player1Label = UiUtil.createStyledLabel("");
        player1Label.setHorizontalAlignment(SwingConstants.LEFT);

        player2Label = UiUtil.createStyledLabel("");
        player2Label.setHorizontalAlignment(SwingConstants.RIGHT);
        player2Label.setHorizontalTextPosition(SwingConstants.LEFT);

        for(JLabel label : new JLabel[] {player1Label, player2Label})
        {
            label.setIconTextGap(10);
            label.setBorder(BorderFactory.createEmptyBorder(12, 20, 12, 20));
        }
        showTurn(1);

        // Home leaves the board and keeps the game, which waits as it is;
        // Abandon gives it up. Even margins, unlike the other buttons, so the
        // text sits on the same line as the names.
        homeBtn = UiUtil.createStyledButton("Home");
        homeBtn.setMargin(new Insets(12, 15, 12, 15));
        UiUtil.addListener(homeBtn, e -> controller.goHome());

        abandonBtn = UiUtil.createStyledButton("Abandon");
        abandonBtn.setMargin(new Insets(12, 15, 12, 15));
        UiUtil.addListener(abandonBtn, e ->
        {
            if(session != null)
            {
                controller.askAbandonGame(session.getId());
            }
        });

        // GridBagLayout keeps the buttons at their own size, side by side and
        // centred in the cell.
        GridBagConstraints between = new GridBagConstraints();
        between.insets = new Insets(0, 5, 0, 5);

        JPanel middle = new JPanel(new GridBagLayout());
        middle.setOpaque(false);
        middle.add(homeBtn, between);
        middle.add(abandonBtn, between);

        // A bar of its own, lighter than the background and closed by a line,
        // so the players are visibly separated from the board. Three equal
        // columns: a very long name is cut with "..." instead of running into
        // the button or into the other player's.
        JPanel header = new JPanel(new GridLayout(1, 3));
        header.setBackground(UiUtil.BACKGROUND_BAR);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 3, 0, UiUtil.ACCENT_SECONDARY));
        header.add(player1Label);
        header.add(middle);
        header.add(player2Label);

        // Under the bar: it says when the opponent is not in this game (he went
        // to the lobby, or to another game), so the player knows why nothing
        // happens. It is always there, blank when there is nothing to say, so
        // the board does not change size when it appears.
        awayLabel = UiUtil.createStyledLabel(" ");
        awayLabel.setFont(awayLabel.getFont().deriveFont(16f));
        awayLabel.setForeground(UiUtil.BACKGROUND_BLACK);
        awayLabel.setBackground(UiUtil.ACCENT);
        awayLabel.setBorder(BorderFactory.createEmptyBorder(5, 20, 5, 20));

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(header, BorderLayout.NORTH);
        top.add(awayLabel, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        boardView = new BoardView();
        add(boardView, BorderLayout.CENTER);

        JPanel controlsPanel = new JPanel(new GridLayout(1, 7, 10, 0));
        controlsPanel.setOpaque(false);

        for (int i = 0; i < 7; i++) 
        {
            final int col = i;
            JButton btn = UiUtil.createStyledButton("Col " + (i + 1));
            UiUtil.addKeyBinding(btn, String.valueOf(i + 1));
            
            UiUtil.addListener(btn, e -> play(col));
            controlsPanel.add(btn);
        }

        // Above the column buttons: what happens in the games that are not on
        // screen. Blank when there is nothing to say, like the strip on top, so
        // the board keeps its size.
        notificationLabel = UiUtil.createStyledLabel(" ");
        notificationLabel.setFont(notificationLabel.getFont().deriveFont(16f));
        notificationLabel.setForeground(Color.WHITE);
        notificationLabel.setBackground(UiUtil.ACCENT_SECONDARY);
        notificationLabel.setBorder(BorderFactory.createEmptyBorder(5, 20, 5, 20));

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(notificationLabel, BorderLayout.NORTH);
        bottom.add(controlsPanel, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
    }

    public void show(GameSession session)
    {
        this.session = session;
        clearNotification();
        // A hidden board does not hear the mouse leave it: forget where it
        // was, the ghost comes back as soon as the mouse moves on the board.
        boardView.setHover(-1);
        refresh();
    }

    // A move in a column, from its button, its key or a click on the board.
    private void play(int col)
    {
        // The keys 1-7 reach this even while a box is open over the board
        // (the box stops only the mouse): the box is answered first
        if (session != null && session.canPlay(col) && !controller.isOverlayOpen())
        {
            controller.sendMessage("MOVE " + session.getId() + " " + col);
        }
    }

    // A message about a game that is not on screen. It goes away by itself:
    // it is not worth a box that stops the game the player is looking at.
    public void showNotification(String text)
    {
        notificationLabel.setText(text);
        notificationLabel.setOpaque(true);
        notificationLabel.repaint();

        if(notificationTimer != null)
        {
            notificationTimer.stop();
        }
        notificationTimer = new javax.swing.Timer(NOTIFICATION_MILLIS, e -> clearNotification());
        notificationTimer.setRepeats(false);
        notificationTimer.start();
    }

    private void clearNotification()
    {
        if(notificationTimer != null)
        {
            notificationTimer.stop();
        }
        notificationLabel.setText(" ");
        notificationLabel.setOpaque(false);
        notificationLabel.repaint();
    }

    // Draws the game on screen again: call it when that game changed.
    public void refresh()
    {
        if(session == null)
            return;
        
        if(session.getPlayerName(1) == controller.getUsername())
        {
            player1Label.setText(session.getPlayerName(1) + " (You)");
            player2Label.setText(session.getPlayerName(2));
        }
        else 
        {
            player1Label.setText(session.getPlayerName(1));
            player2Label.setText(session.getPlayerName(2) + " (You)");
        }
        
        showTurn(session.getTurn());

        boolean away = session.isOpponentAway();
        awayLabel.setOpaque(away);
        awayLabel.setText(away ? session.getOpponent() + " is away from this game" : " ");
        awayLabel.repaint();

        boardView.repaint();
    }

    // The player on turn is at full brightness and the other one is dimmed.
    // When the game is over (turn 0) both are back to normal.
    private void showTurn(int turn)
    {
        styleBadge(player1Label, UiUtil.ERROR_RED, turn != 2);
        styleBadge(player2Label, UiUtil.SUCCESS_GREEN, turn != 1);
    }

    private void styleBadge(JLabel label, Color color, boolean active)
    {
        int alpha = active ? 255 : 90;
        label.setIcon(new UiUtil.PersonIcon(36, withAlpha(color, alpha)));
        label.setForeground(withAlpha(Color.WHITE, alpha));
    }

    private static Color withAlpha(Color color, int alpha)
    {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private class BoardView extends JPanel
    {
        // The column under the mouse, -1 when the mouse is not on the board.
        private int hoverCol = -1;

        public BoardView()
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
                        play(col);
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
            int col = x / (getWidth() / 7);
            return col < 7 ? col : -1;
        }

        @Override
        protected void paintComponent(Graphics g) 
        {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            String board = (session != null) ? session.getBoard() : GameSession.EMPTY_BOARD;

            int cellWidth = getWidth() / 7;
            int cellHeight = getHeight() / 6;
            int diameter = Math.min(cellWidth, cellHeight) - 10;

            // The ghost: where my disc would land in the column under the
            // mouse. Only where I could play, so it goes away by itself when
            // the turn passes, the column fills up or the game is over.
            int ghostRow = -1;
            Color ghostColor = null;
            if (session != null && hoverCol >= 0 && session.canPlay(hoverCol))
            {
                ghostRow = session.landingRow(hoverCol);
                ghostColor = withAlpha(session.getMyPlayer() == 1 ? UiUtil.ERROR_RED : UiUtil.SUCCESS_GREEN, 90);
            }

            for (int row = 0; row < 6; row++)
            {
                for (int col = 0; col < 7; col++) 
                {
                    int x = col * cellWidth + (cellWidth - diameter) / 2;
                    int y = row * cellHeight + (cellHeight - diameter) / 2;

                    char cell = board.charAt(row * 7 + col);
                    if (cell == '1') 
                    {
                        g2d.setColor(UiUtil.ERROR_RED); 
                    } 
                    else if (cell == '2') 
                    {
                        g2d.setColor(UiUtil.SUCCESS_GREEN); 
                    } 
                    else 
                    {
                        g2d.setColor(UiUtil.BACKGROUND_GRAY); 
                    }

                    g2d.fillOval(x, y, diameter, diameter);

                    // See-through, on top of the empty hole.
                    if (row == ghostRow && col == hoverCol)
                    {
                        g2d.setColor(ghostColor);
                        g2d.fillOval(x, y, diameter, diameter);
                    }

                    g2d.setColor(UiUtil.ACCENT);
                    g2d.drawOval(x, y, diameter, diameter);
                }
            }
        }
    }
}