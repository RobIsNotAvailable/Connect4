package com.lso;

import java.awt.CardLayout;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import com.lso.view.MainFrame;
import com.lso.view.GamePanel;
import com.lso.view.LobbyPanel;
import com.lso.view.OverlayPanel;

public class MainController
{
    private MainFrame mainFrame;
    private JPanel mainPanel;
    private CardLayout cardLayout;
    private PrintWriter out;
    private String username;
    // WIN, LOSE or DRAW of the last game, kept to redraw the rematch box when
    // the opponent asks for a rematch.
    private String gameResult;
    private String currentScreen = "Lobby";

    // Things that interrupt the player (a join request, an error) must not
    // replace what the overlay is already showing: a Game Over box swallowed
    // by a notice would leave the player without Rematch / Leave room. So a
    // notice waits in this queue until the overlay is free.
    private final Deque<Notice> pendingNotices = new ArrayDeque<>();
    private Notice shownNotice;

    private LobbyPanel lobbyPanel;
    private GamePanel gamePanel;
    private OverlayPanel overlay;

    private static class Notice
    {
        // Id of the room when the notice is a join request, otherwise null.
        // Join requests wait for the lobby instead of interrupting a game,
        // and can be withdrawn by the joiner (JOIN_CANCELLED).
        final String joinRoom;
        final Runnable show;

        Notice(String joinRoom, Runnable show)
        {
            this.joinRoom = joinRoom;
            this.show = show;
        }
    }

    public MainController()
    {
        mainFrame = new MainFrame();
        overlay = new OverlayPanel();
        mainFrame.setGlassPane(overlay);
        cardLayout = new CardLayout();
        mainPanel = new JPanel(cardLayout);
        mainPanel.setOpaque(false);

        lobbyPanel = new LobbyPanel(this);
        mainPanel.add(lobbyPanel, "Lobby"); 

        gamePanel = new GamePanel(this);
        mainPanel.add(gamePanel, "Game");
        
        mainFrame.add(mainPanel);

        startNetwork();
    }

    public void showScreen(String screenName)
    {
        currentScreen = screenName;
        cardLayout.show(mainPanel, screenName);
    }

    private boolean inGame()
    {
        return currentScreen.equals("Game");
    }

    private void notice(String joinRoom, Runnable show)
    {
        pendingNotices.add(new Notice(joinRoom, show));
        showNextNotice();
    }

    private void showNotice(String title, String message)
    {
        notice(null, () -> overlay.showChoice(
            title,
            message,
            new String[] {"OK"},
            choice -> closeOverlay()
        ));
    }

    private void showNextNotice()
    {
        if(overlay.isVisible())
        {
            return;
        }

        for(Iterator<Notice> it = pendingNotices.iterator(); it.hasNext();)
        {
            Notice next = it.next();
            if(next.joinRoom == null || !inGame())
            {
                it.remove();
                shownNotice = next;
                next.show.run();
                return;
            }
        }
    }

    // Every box is closed through here, so the next notice in line appears.
    private void closeOverlay()
    {
        overlay.close();
        shownNotice = null;
        showNextNotice();
    }

    // A box the player must answer (Game Over, opponent left) replaces a
    // notice that is on screen: the notice goes back to the front of the
    // queue and comes back once the overlay is free again.
    private void displaceNotice()
    {
        if(shownNotice != null)
        {
            pendingNotices.addFirst(shownNotice);
            shownNotice = null;
        }
    }

    private void startNetwork()
    {
        new Thread(() ->
        {
            boolean connected = false;
            boolean welcomed = false;

            try (Socket socket = new Socket("127.0.0.1", 8080);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream())))
            {
                connected = true;
                out = new PrintWriter(socket.getOutputStream(), true);

                String line;
                while((line = in.readLine()) != null)
                {
                    if(line.startsWith("WELCOME"))
                    {
                        welcomed = true;
                    }

                    String msg = line;
                    SwingUtilities.invokeLater(() ->
                    {
                        handleServerMessage(msg);
                    });
                }
            }
            catch(Exception e)
            {
                System.err.println("Connection error: " + e.getMessage());
            }

            // Getting here means the connection is over. How far it got says
            // why: a full server closes it without even sending WELCOME.
            String problem;
            if(!connected)
            {
                problem = "Could not reach the server.";
            }
            else if(!welcomed)
            {
                problem = "The server closed the connection. It may be full.";
            }
            else
            {
                problem = "The connection to the server was lost.";
            }
            SwingUtilities.invokeLater(() -> showConnectionError(problem));
        }).start();
    }

    // Without the server nothing else works, so Quit is the only choice. It
    // replaces whatever the overlay was showing, and the notices waiting in
    // the queue no longer matter.
    private void showConnectionError(String problem)
    {
        pendingNotices.clear();
        shownNotice = null;
        overlay.showChoice(
            "Connection error",
            problem,
            new String[] {"Quit"},
            choice -> System.exit(0)
        );
    }

    private void handleServerMessage(String msg)
    {
        System.out.println("Received: " + msg);
        String[] parts = msg.split(" ");
        String cmd = parts[0];

        switch(cmd)
        {
            case "WELCOME":
                askUsername("Choose a username:");
                break;

            case "USERNAME_SET":
                username = NameCodec.decode(parts[1]);
                lobbyPanel.setUsername(username);
                closeOverlay();
                sendMessage("LIST_GAMES");
                break;

            case "GAME_LIST":
                int count = Integer.parseInt(parts[1]);
                Object[][] data = new Object[count][3];
                int index = 2;

                for(int i = 0; i < count; i++)
                {
                    data[i][0] = parts[index++];
                    data[i][1] = NameCodec.decode(parts[index++]);
                    data[i][2] = NameCodec.decode(parts[index++]);
                }
                lobbyPanel.updateGameList(data);
                break;

            case "GAME_CREATED":
            case "NEW_GAME":
            case "GAME_CLOSED":
            case "GAME_IN_PROGRESS":
                sendMessage("LIST_GAMES");
                break;

            case "JOIN_NOTIFY":
                askJoinRequest(parts[1], NameCodec.decode(parts[2]));
                break;

            case "JOIN_CANCELLED":
                cancelJoinRequest(parts[1]);
                break;

            case "JOIN_RESULT":
                lobbyPanel.clearStatus();
                if(parts[2].equals("0"))
                {
                    showNotice("Access Denied", "Your request was rejected by the owner.");
                }
                break;

            case "GAME_START":
                lobbyPanel.clearStatus();
                int id = Integer.parseInt(parts[1]);
                int myPlayer = Integer.parseInt(parts[2]);
                String opponent = NameCodec.decode(parts[3]);

                gamePanel.setupGame(id, myPlayer, username, opponent);
                // Screen first: the box that closes now may make a waiting
                // join request appear, and it must see where we are.
                showScreen("Game");
                closeOverlay();
                break;

            case "GAME_STATE":
                int turn = Integer.parseInt(parts[2]);
                String boardStr = parts[3];
                gamePanel.updateState(turn, boardStr);
                break;

            case "GAME_OVER":
                gameResult = parts[2];
                askRematch(parts[1], gameResult, false);
                break;

            // The opponent voted for a rematch first: the same box is drawn
            // again, now saying so. It only arrives to a player who has not
            // voted yet. If we already went back to the lobby ourselves it
            // is stale and is ignored.
            case "REMATCH_NOTIFY":
                if(inGame())
                {
                    askRematch(parts[1], gameResult, true);
                }
                break;

            // The opponent left: the room is open again and we own it. This
            // replaces whatever the overlay was showing (the rematch vote or
            // its "waiting" message). If we already went back to the lobby
            // ourselves the message is stale and is ignored.
            case "OPPONENT_LEFT":
                if(inGame())
                {
                    displaceNotice();
                    overlay.showChoice(
                        "Game Over",
                        "Your opponent left the room.",
                        new String[] {"Leave room"},
                        button -> leaveRoom(parts[1])
                    );
                }
                break;

            case "ERROR":
                handleError(parts[1], parts[2]);
                break;

            default:
                System.out.println("Unhandled command: " + cmd);
                break;
        }
    }

    // The server refuses every command until a username is set, so this is
    // the first thing the client does. Quit closes the app.
    private void askUsername(String prompt)
    {
        overlay.showInput(
            "Username",
            prompt,
            "OK",
            name -> sendMessage("SET_USERNAME " + NameCodec.encode(name.trim())),
            "Quit",
            () -> System.exit(0)
        );
    }

    // Same overlay as the username, so it stays inside the window. An empty
    // name keeps it open, Cancel closes it.
    public void askRoomName()
    {
        overlay.showInput(
            "Create Game",
            "Room name (up to 20 characters):",
            "OK",
            name ->
            {
                if(!name.trim().isEmpty())
                {
                    closeOverlay();
                    sendMessage("CREATE_GAME " + NameCodec.encode(name.trim()));
                }
            },
            "Cancel",
            () -> closeOverlay()
        );
    }

    // The creator leaving a waiting room alone is how a room is deleted
    // (docs/protocol.md §7). The others are told with GAME_CLOSED but the
    // sender only gets GAME_LEFT, so the list is asked again.
    public void askDeleteRoom(String gameId, String roomName)
    {
        overlay.showChoice(
            "Delete Room",
            "Delete \"" + roomName + "\"?",
            new String[] {"Delete", "Cancel"},
            choice ->
            {
                closeOverlay();
                if(choice == 0)
                {
                    sendMessage("LEAVE_GAME " + gameId);
                    sendMessage("LIST_GAMES");
                }
            }
        );
    }

    // Leaving in the middle of a game gives the win to nobody: the opponent
    // stays alone in the room, which is waiting for players again.
    public void askLeaveGame(String gameId)
    {
        overlay.showChoice(
            "Leave Game",
            "Leave the game? Nobody wins.",
            new String[] {"Leave", "Stay"},
            choice ->
            {
                if(choice == 0)
                {
                    leaveRoom(gameId);
                }
                else
                {
                    closeOverlay();
                }
            }
        );
    }

    // A player asks to join a room of ours. Both answers go to the server,
    // which starts the game on Accept.
    private void askJoinRequest(String gameId, String joiner)
    {
        notice(gameId, () -> overlay.showChoice(
            "Join Request",
            joiner + " wants to join your game. Accept?",
            new String[] {"Accept", "Decline"},
            choice ->
            {
                sendMessage("JOIN_RESPONSE " + gameId + " " + (choice == 0 ? 1 : 0));
                closeOverlay();
            }
        ));
    }

    // The joiner disconnected before we answered: the request is gone, so
    // its box must not stay on screen or wait in the queue.
    private void cancelJoinRequest(String gameId)
    {
        pendingNotices.removeIf(n -> gameId.equals(n.joinRoom));

        if(shownNotice != null && gameId.equals(shownNotice.joinRoom))
        {
            closeOverlay();
        }
    }

    private void handleError(String command, String code)
    {
        if(command.equals("SET_USERNAME"))
        {
            askUsername("Username not accepted (" + code + "). Choose another one:");
            return;
        }

        // The buttons already refuse a move that is not allowed, so these two
        // can only come from a race with a GAME_STATE that is about to
        // arrive: the board on screen will be right in a moment.
        if(command.equals("MOVE") && (code.equals("NOT_YOUR_TURN") || code.equals("COLUMN_FULL")))
        {
            return;
        }

        if(command.equals("JOIN_GAME"))
        {
            lobbyPanel.clearStatus();
        }

        showNotice("Error", errorText(command, code));
    }

    private static String errorText(String command, String code)
    {
        switch(code)
        {
            case "NOT_FOUND":       return "That room no longer exists.";
            case "NOT_WAITING":     return "That room is no longer waiting for players.";
            case "SELF_JOIN":       return "You can't join your own room.";
            case "ALREADY_PENDING": return "You have already asked for this.";
            case "TOO_MANY_GAMES":  return "You already have 3 rooms. Delete one to create another.";
            case "SERVER_FULL":     return "The server can't host more games right now.";
            case "INVALID_NAME":    return "That name is not valid: use 1 to 20 printable characters.";
            case "ALREADY_PLAYING": return "You are already playing another game.";
            case "JOINER_BUSY":     return "That player is already in another game.";
            case "OPPONENT_BUSY":   return "Your opponent started another game.";
            case "NO_PENDING":      return "That request is no longer valid.";
            default:                return "Unexpected server error (" + command + ": " + code + ").";
        }
    }

    // The room survives the end of the game, so the player has to choose:
    // vote for a rematch (it starts only if the opponent votes too) or leave
    // the room. After voting the same overlay switches to a waiting message,
    // which is closed by the GAME_START of the rematch; a vote can't be
    // withdrawn, so leaving stays the only way out. If the opponent has
    // already voted the message says so.
    private void askRematch(String gameId, String result, boolean opponentWants)
    {
        displaceNotice();

        String message;
        if(result.equals("WIN"))
        {
            message = "You won!";
        }
        else if(result.equals("LOSE"))
        {
            message = "You lost.";
        }
        else
        {
            message = "It's a draw.";
        }

        if(opponentWants)
        {
            message += " Your opponent wants a rematch.";
        }

        overlay.showChoice(
            "Game Over",
            message,
            new String[] {"Rematch", "Leave room"},
            choice ->
            {
                if(choice == 0)
                {
                    sendMessage("REMATCH " + gameId);
                    overlay.showChoice(
                        "Game Over",
                        "Waiting for the opponent's decision...",
                        new String[] {"Leave room"},
                        waitingChoice -> leaveRoom(gameId)
                    );
                }
                else
                {
                    leaveRoom(gameId);
                }
            }
        );
    }

    private void leaveRoom(String gameId)
    {
        // Lobby first: closing the box may bring up a join request that was
        // waiting for it.
        showScreen("Lobby");
        closeOverlay();
        sendMessage("LEAVE_GAME " + gameId);
        sendMessage("LIST_GAMES");
    }

    public void sendMessage(String msg)
    {
        if(out != null)
        {
            out.println(msg);
        }
    }

    static void setLaF()
    {
        try
        {
            javax.swing.UIManager.setLookAndFeel("com.formdev.flatlaf.FlatDarkLaf");
            JFrame.setDefaultLookAndFeelDecorated(true);
        }
        catch(Exception e)
        {
            System.err.println("Failed to set Look and Feel: " + e.getMessage());
        }
    }

    public static void main(String[] args) 
    { 
        SwingUtilities.invokeLater(() ->
        {
            setLaF();
            new MainController();
        });
    }
}