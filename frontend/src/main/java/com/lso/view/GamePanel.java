package com.lso.view;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import com.lso.MainController;

public class GamePanel extends JPanel
{
    private JLabel player1Label;
    private JLabel player2Label;
    private BoardView boardView;
    private String currentBoard = "..........................................";
    private int currentGameId = -1;
    private boolean finished = false;

    public GamePanel(MainController controller)
    {
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

        // Two equal halves: a very long name is cut with "..." instead of
        // running into the other player's.
        // A bar of its own, lighter than the background and closed by a line,
        // so the players are visibly separated from the board.
        JPanel header = new JPanel(new GridLayout(1, 2));
        header.setBackground(UiUtil.BACKGROUND_BAR);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 3, 0, UiUtil.ACCENT_SECONDARY));
        header.add(player1Label);
        header.add(player2Label);
        add(header, BorderLayout.NORTH);

        boardView = new BoardView();
        add(boardView, BorderLayout.CENTER);

        JPanel controlsPanel = new JPanel(new GridLayout(1, 7, 10, 0));
        controlsPanel.setOpaque(false);

        for (int i = 0; i < 7; i++) 
        {
            final int col = i;
            JButton btn = UiUtil.createStyledButton("Col " + (i + 1));
            UiUtil.addKeyBinding(btn, String.valueOf(i + 1));
            
            UiUtil.addListener(btn, e -> 
            {
                // The keys 1-7 also work while the game over overlay is open
                if (currentGameId != -1 && !finished)
                {
                    controller.sendMessage("MOVE " + currentGameId + " " + col);
                }
            });
            controlsPanel.add(btn);
        }
        add(controlsPanel, BorderLayout.SOUTH);
    }

    public void setupGame(int gameId, int myPlayer, String myName, String opponent)
    {
        this.currentGameId = gameId;
        this.finished = false;
        player1Label.setText(myPlayer == 1 ? myName : opponent);
        player2Label.setText(myPlayer == 2 ? myName : opponent);
        showTurn(1);
    }

    public void updateState(int turn, String boardStr)
    {
        this.currentBoard = boardStr;
        this.finished = (turn == 0);
        showTurn(turn);
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
        public BoardView() 
        {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) 
        {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int cellWidth = getWidth() / 7;
            int cellHeight = getHeight() / 6;
            int diameter = Math.min(cellWidth, cellHeight) - 10;

            for (int row = 0; row < 6; row++) 
            {
                for (int col = 0; col < 7; col++) 
                {
                    int x = col * cellWidth + (cellWidth - diameter) / 2;
                    int y = row * cellHeight + (cellHeight - diameter) / 2;

                    char cell = currentBoard.charAt(row * 7 + col);
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
                    g2d.setColor(UiUtil.ACCENT);
                    g2d.drawOval(x, y, diameter, diameter);
                }
            }
        }
    }
}