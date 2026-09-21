package com.lso.view;

import javax.swing.JPanel;
import javax.swing.JButton;
import javax.swing.JLabel;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import com.lso.MainController;

public class GamePanel extends JPanel 
{
    private JLabel statusLabel;
    private BoardView boardView;
    private String currentBoard = "..........................................";
    private int myPlayer = 0; 
    private int currentGameId = -1;
    private boolean finished = false;

    public GamePanel(MainController controller) 
    {
        setLayout(new BorderLayout(0, 20));
        setOpaque(false);

        statusLabel = UiUtil.createStyledLabel("Waiting for game to start...");
        add(statusLabel, BorderLayout.CENTER);

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

    public void setupGame(int gameId, int myPlayer, String opponent) 
    {
        this.currentGameId = gameId;
        this.myPlayer = myPlayer;
        this.finished = false;
        statusLabel.setText("Playing against: " + opponent + " | You are Player " + myPlayer);
    }

    public void updateState(int turn, String boardStr) 
    {
        this.currentBoard = boardStr;
        this.finished = (turn == 0);
        if (turn == 0)
        {
            statusLabel.setText("Game Over!");
        } 
        else if (turn == myPlayer) 
        {
            statusLabel.setText("Your turn!");
        } 
        else 
        {
            statusLabel.setText("Opponent's turn...");
        }
        boardView.repaint();
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