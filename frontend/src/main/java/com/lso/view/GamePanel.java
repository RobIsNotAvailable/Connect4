package com.lso.view;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import com.lso.GameSession;
import com.lso.controller.GameController;
import com.lso.controller.GameEndController;

public class GamePanel extends JPanel
{
    // How long a notification stays on screen.
    public static final int NOTIFICATION_MILLIS = 6000;

    private final GameController games;
    private final GameEndController ends;
    private JLabel player1Label;
    private JLabel player2Label;
    private BoardView boardView;
    // The game on screen. All the state (board, turn, names) is in it; the
    // panel only draws it and turns the clicks into messages.
    private GameSession session;
    private JLabel awayLabel;
    private JLabel notificationLabel;
    private javax.swing.Timer notificationTimer;

    public GamePanel(GameController games, GameEndController ends, GameSession session)
    {
        this.games = games;
        this.ends = ends;
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

        // Home leaves the board and keeps the game, which waits as it is;
        // Abandon gives it up. Even margins, unlike the other buttons, so the
        // text sits on the same line as the names.
        JButton homeBtn = UiUtil.createStyledButton("Home");
        homeBtn.setMargin(new Insets(12, 15, 12, 15));
        homeBtn.addActionListener(e -> games.goHome());

        JButton abandonBtn = UiUtil.createStyledButton("Abandon");
        abandonBtn.setMargin(new Insets(12, 15, 12, 15));
        abandonBtn.addActionListener(e -> ends.askAbandonGame(this.session.getId()));

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
        awayLabel = UiUtil.createStripLabel(UiUtil.ACCENT, UiUtil.BACKGROUND_BLACK);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(header, BorderLayout.NORTH);
        top.add(awayLabel, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        boardView = new BoardView(this::play);
        add(boardView, BorderLayout.CENTER);

        JPanel controlsPanel = new JPanel(new GridLayout(1, GameSession.COLUMNS, 10, 0));
        controlsPanel.setOpaque(false);

        for (int i = 0; i < GameSession.COLUMNS; i++)
        {
            final int col = i;
            JButton btn = UiUtil.createStyledButton("Col " + (i + 1));
            UiUtil.addKeyBinding(btn, String.valueOf(i + 1));
            
            btn.addActionListener(e -> play(col));
            controlsPanel.add(btn);
        }

        // Above the column buttons: what happens in the games that are not on
        // screen. Blank when there is nothing to say, like the strip on top, so
        // the board keeps its size.
        notificationLabel = UiUtil.createStripLabel(UiUtil.ACCENT_SECONDARY, Color.WHITE);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(notificationLabel, BorderLayout.NORTH);
        bottom.add(controlsPanel, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        show(session);
    }

    // The game to show: the one it was made for, then its rematches.
    public void show(GameSession session)
    {
        this.session = session;
        clearNotification();
        boardView.setSession(session);
        refresh();
    }

    public GameSession getSession()
    {
        return session;
    }

    // A move in a column, from its button, its key or a click on the board.
    private void play(int col)
    {
        if (session.canPlay(col))
        {
            games.move(session.getId(), col);
        }
    }

    // A message about a game that is not on screen. It goes away by itself:
    // it is not worth a box that stops the game the player is looking at.
    public void showNotification(String text)
    {
        UiUtil.setStrip(notificationLabel, text);

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
        UiUtil.setStrip(notificationLabel, null);
    }

    // Draws the game on screen again: call it when that game changed.
    public void refresh()
    {
        int me = session.getMyPlayer();
        player1Label.setText(session.getPlayerName(1) + (me == 1 ? " (You)" : ""));
        player2Label.setText(session.getPlayerName(2) + (me == 2 ? " (You)" : ""));
        
        showTurn(session.getTurn());

        UiUtil.setStrip(awayLabel, session.isOpponentAway() ? session.getOpponent() + " is away from this game" : null);

        boardView.repaint();
    }

    // The player on turn is at full brightness and the other one is dimmed.
    // When the game is over (turn 0) both are back to normal.
    private void showTurn(int turn)
    {
        styleBadge(player1Label, UiUtil.playerColor(1), turn != 2);
        styleBadge(player2Label, UiUtil.playerColor(2), turn != 1);
    }

    private void styleBadge(JLabel label, Color color, boolean active)
    {
        int alpha = active ? 255 : 90;
        label.setIcon(new UiUtil.PersonIcon(36, UiUtil.withAlpha(color, alpha)));
        label.setForeground(UiUtil.withAlpha(Color.WHITE, alpha));
    }
}
