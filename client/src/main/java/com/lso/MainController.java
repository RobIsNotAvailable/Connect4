package com.lso;

import java.awt.CardLayout;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import com.lso.controller.GameController;
import com.lso.controller.GameEndController;
import com.lso.controller.JoinController;
import com.lso.controller.LobbyController;
import com.lso.view.Dialogs;
import com.lso.view.ErrorText;
import com.lso.view.LobbyPanel;
import com.lso.view.MainFrame;
import com.lso.view.OverlayPanel;
import com.lso.view.OverlayPanel.Choice;
import com.lso.view.UiUtil;

// Puts the client together and hands every line of the server to the
// controller it is about: LobbyController (the username, the lists, creating
// and deleting rooms), JoinController (join requests), GameController (the
// games, their tabs) and GameEndController (the end of a game, the rematch,
// leaving it). The views call those controllers directly.
public class MainController
{
    // MAX_GAMES_PER_PLAYER in the server: the games a player can be in at once,
    // the rooms of ours that wait for an opponent included.
    public static final int MAX_MATCHES = 5;

    private MainFrame mainFrame;
    private JPanel mainPanel;
    private CardLayout cardLayout;
    private final ServerConnection connection = new ServerConnection();
    private Dialogs dialogs;
    private LobbyPanel lobbyPanel;
    private LobbyController lobby;
    private JoinController joins;
    private GameController games;
    private GameEndController ends;

    public MainController()
    {
        mainFrame = new MainFrame();
        OverlayPanel overlay = new OverlayPanel();
        mainFrame.setGlassPane(overlay);
        dialogs = new Dialogs(overlay, () -> games.isViewing());
        cardLayout = new CardLayout();
        mainPanel = new JPanel(cardLayout);
        mainPanel.setOpaque(false);

        lobbyPanel = new LobbyPanel();
        mainPanel.add(lobbyPanel, "Lobby");
        joins = new JoinController(connection, dialogs, lobbyPanel);

        games = new GameController(this, connection, dialogs);
        dialogs.onJoinRequests(games::setWaitingRequests);
        mainPanel.add(games.screen(), "Games");
        ends = games.ends();
        lobby = new LobbyController(connection, dialogs, lobbyPanel, games);
        lobbyPanel.connect(lobby, joins, games);

        mainFrame.add(mainPanel);

        connection.start(this::handleServerMessage, this::showConnectionError);
    }

    // The two screens of the window.
    public void showLobby()
    {
        cardLayout.show(mainPanel, "Lobby");
    }

    public void showGames()
    {
        cardLayout.show(mainPanel, "Games");
    }

    // Without the server nothing else works, so Quit is the only choice. It
    // replaces whatever the overlay was showing, and the notices waiting in
    // the queue no longer matter.
    private void showConnectionError(String problem)
    {
        dialogs.showOnly("Connection Error", problem, new Choice("Quit", () -> System.exit(0)));
    }

    private void handleServerMessage(String msg)
    {
        System.out.println("Received: " + msg);
        String[] parts = msg.split(" ");
        String cmd = parts[0];

        switch(cmd)
        {
            case "WELCOME":
                lobby.askUsername("Choose a username:");
                break;

            case "USERNAME_SET":
                lobby.onUsernameSet(NameCodec.decode(parts[1]));
                break;

            case "GAME_LIST":
                lobby.onGameList(parts);
                break;

            case "MY_GAME_LIST":
                lobby.onMyGameList(parts);
                break;

            case "GAME_CREATED":
                connection.send("LIST_GAMES");
                connection.send("LIST_MY_GAMES");
                break;

            // The answer to our LEAVE_GAME: the front already left the game
            // when it sent it, so there is nothing left to do.
            case "GAME_LEFT":
                break;

            case "NEW_GAME":
            case "GAME_IN_PROGRESS":
                connection.send("LIST_GAMES");
                break;

            // A room left the list of the ones to join, because it was deleted
            // or its game ended. It is never about a game of ours: a room is
            // deleted only when its last player leaves it, and the players of a
            // game that ends are told with GAME_OVER.
            case "GAME_CLOSED":
                joins.onRoomClosed(Integer.parseInt(parts[1]));
                connection.send("LIST_GAMES");
                break;

            case "JOIN_NOTIFY":
                joins.ask(parts[1], lobby.roomName(parts[1]), NameCodec.decode(parts[2]));
                break;

            case "JOIN_CANCELLED":
                // The joiner disconnected before we answered.
                dialogs.cancelJoinRequest(parts[1]);
                break;

            case "JOIN_RESULT":
                joins.onResult(Integer.parseInt(parts[1]), parts[2].equals("1"), lobby.myGamesCount());
                break;

            case "GAME_START":
                joins.onGameStart(Integer.parseInt(parts[1]));
                games.onGameStart(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), lobby.username(), NameCodec.decode(parts[3]));
                break;

            case "GAME_STATE":
                games.onGameState(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), parts[3]);
                break;

            case "GAME_OVER":
                ends.onGameOver(Integer.parseInt(parts[1]), GameSession.Result.valueOf(parts[2]));
                break;

            case "REMATCH_NOTIFY":
                ends.onRematchNotify(Integer.parseInt(parts[1]));
                break;

            case "OPPONENT_LEFT":
                ends.onOpponentLeft(Integer.parseInt(parts[1]));
                break;

            case "OPPONENT_STATUS":
                games.onOpponentStatus(Integer.parseInt(parts[1]), parts[2].equals("AWAY"));
                break;

            case "ERROR":
                handleError(parts[1], parts[2]);
                break;

            default:
                System.out.println("Unhandled command: " + cmd);
                break;
        }
    }

    // Every ERROR goes to the part that sent the refused command. What is left
    // is shown in a box.
    private void handleError(String command, String code)
    {
        switch(command)
        {
            case "SET_USERNAME":
                lobby.onUsernameRefused(code);
                return;

            // The buttons already refuse a move that is not allowed, so the
            // other refusals can only come from a race: with a GAME_STATE that
            // is about to arrive (the board on screen will be right in a
            // moment), or with the opponent leaving, which OPPONENT_LEFT tells.
            // The same goes for a rematch voted as the opponent leaves or voted
            // twice, and for leaving a game we are already out of.
            case "MOVE":
                if(code.equals("NOT_ACTIVE"))
                {
                    games.onNotActive();
                }
                return;

            case "REMATCH":
            case "LEAVE_GAME":
                return;

            // Nobody asked for this one: the front does it by itself. If it
            // fails the game is gone, and OPPONENT_LEFT is what tells the player.
            case "SET_ACTIVE_GAME":
                System.out.println("Could not make the game active: " + code);
                return;

            case "CREATE_GAME":
                if(code.equals("INVALID_NAME"))
                {
                    lobby.onRoomNameRefused(code);
                    return;
                }
                break;

            case "JOIN_GAME":
                joins.onRefused();
                break;
        }

        dialogs.notice("Error", ErrorText.of(code));
    }

    public static void main(String[] args) 
    { 
        SwingUtilities.invokeLater(() ->
        {
            UiUtil.installLookAndFeel();
            new MainController();
        });
    }
}