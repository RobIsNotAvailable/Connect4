package com.lso;

import java.awt.CardLayout;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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

    // The games in progress, by id: a player can be in several at once, and
    // every message of the server names the one it is about. 'viewed' is the
    // one on the game screen, or null while the lobby is shown.
    private final Map<Integer, GameSession> sessions = new HashMap<>();
    private GameSession viewed;

    // The rows of My Games come from two lists of the server, which answer one
    // after the other: the games we play, and the rooms of ours that still wait
    // for an opponent. Each is kept here and the table is drawn from both.
    private List<Object[]> matchRows = new ArrayList<>();
    private List<Object[]> waitingRows = new ArrayList<>();

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

    private boolean inGame()
    {
        return viewed != null;
    }

    // Puts a game on the game screen. The server already made it the active
    // game if the player had nothing else to do; saying so anyway costs
    // nothing (it is a no-op then) and keeps client and server agreeing about
    // which game takes moves.
    private void viewGame(GameSession session)
    {
        viewed = session;
        gamePanel.show(session);
        refreshWaitingCount();
        cardLayout.show(mainPanel, "Game");
        sendMessage("SET_ACTIVE_GAME " + session.getId());
    }

    private void viewLobby()
    {
        viewed = null;
        cardLayout.show(mainPanel, "Lobby");
        requestMyGames();
    }

    // Something happened in a game that is not on screen, and the player is on
    // another board: a line on that board says so. In the lobby there is no
    // need, the list of the games shows it.
    private void notifyBackground(GameSession session, String text)
    {
        if(viewed != null && session != viewed)
        {
            gamePanel.showNotification(text);
        }
    }

    // The counter on the Home button: the other games where it is our move.
    private void refreshWaitingCount()
    {
        int waiting = 0;
        for(GameSession session : sessions.values())
        {
            if(session != viewed && session.getTurn() == session.getMyPlayer())
            {
                waiting++;
            }
        }
        gamePanel.setOtherGamesWaiting(waiting);
    }

    private static String resultText(String result)
    {
        if(result.equals("WIN"))
        {
            return "you won";
        }
        return result.equals("LOSE") ? "you lost" : "draw";
    }

    // The list of our games is only on the lobby, so it is only asked for while
    // the lobby is shown: on the way in, and again whenever something happens
    // to one of the games (a move, an end, an opponent that leaves or moves) or
    // to one of our rooms (created, deleted).
    private void requestMyGames()
    {
        if(viewed == null)
        {
            sendMessage("LIST_MY_MATCHES");
            sendMessage("LIST_MY_GAMES");
        }
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
                requestMyGames();
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

            case "MY_MATCH_LIST":
                onMatchList(parts);
                break;

            case "MY_GAME_LIST":
                onOwnedList(parts);
                break;

            case "GAME_CREATED":
                sendMessage("LIST_GAMES");
                requestMyGames();
                break;

            case "NEW_GAME":
            case "GAME_IN_PROGRESS":
                sendMessage("LIST_GAMES");
                break;

            case "GAME_CLOSED":
                onGameClosed(Integer.parseInt(parts[1]));
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
                onGameStart(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), NameCodec.decode(parts[3]));
                break;

            case "GAME_STATE":
                onGameState(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), parts[3]);
                break;

            case "GAME_OVER":
                onGameOver(Integer.parseInt(parts[1]), parts[2]);
                break;

            case "REMATCH_NOTIFY":
                onRematchNotify(Integer.parseInt(parts[1]));
                break;

            case "OPPONENT_LEFT":
                onOpponentLeft(Integer.parseInt(parts[1]));
                break;

            case "OPPONENT_STATUS":
                onOpponentStatus(Integer.parseInt(parts[1]), parts[2]);
                break;

            case "ERROR":
                handleError(parts[1], parts[2]);
                break;

            default:
                System.out.println("Unhandled command: " + cmd);
                break;
        }
    }

    // A game starts: an accepted join, or a rematch, which reuses the id of the
    // game and so replaces its session. The game takes the screen only if
    // nothing else is on it (or if it is the game on it already): a player in
    // the middle of another game must not be pulled out of it.
    private void onGameStart(int id, int myPlayer, String opponent)
    {
        lobbyPanel.clearStatus();

        GameSession session = new GameSession(id, myPlayer, username, opponent);
        sessions.put(id, session);

        if(viewed == null || viewed.getId() == id)
        {
            viewGame(session);
            closeOverlay();
        }
        else
        {
            // The server makes the new game active by itself if the game on
            // screen is over. It is not the one we are looking at, so say
            // which one is.
            sendMessage("SET_ACTIVE_GAME " + viewed.getId());
            notifyBackground(session, "New game against " + opponent);
            refreshWaitingCount();
        }
    }

    // A message can name a game we no longer have: we left it while the
    // message was on its way. It is ignored, like the ones below.
    private void onGameState(int id, int turn, String board)
    {
        GameSession session = sessions.get(id);
        if(session == null)
        {
            return;
        }

        boolean wasMyTurn = (session.getTurn() == session.getMyPlayer());
        session.update(turn, board);

        if(session == viewed)
        {
            gamePanel.refresh();
        }
        else if(!wasMyTurn && session.getTurn() == session.getMyPlayer())
        {
            notifyBackground(session, "Your turn against " + session.getOpponent());
        }
        refreshWaitingCount();
        requestMyGames();
    }

    // What the player does at the end of a game is asked only on the game they
    // are looking at. The result is kept in the session for the others.
    private void onGameOver(int id, String result)
    {
        GameSession session = sessions.get(id);
        if(session == null)
        {
            return;
        }

        session.setResult(result);
        if(session == viewed)
        {
            showGameOver(session);
        }
        else
        {
            notifyBackground(session, "Game against " + session.getOpponent() + " is over: " + resultText(result));
        }
        refreshWaitingCount();
        requestMyGames();
    }

    // The opponent voted for a rematch first: the same box is drawn again, now
    // saying so. It only arrives to a player who has not voted yet.
    private void onRematchNotify(int id)
    {
        GameSession session = sessions.get(id);
        if(session == null)
        {
            return;
        }

        session.setOpponentWantsRematch(true);
        if(session == viewed)
        {
            showGameOver(session);
        }
        else
        {
            notifyBackground(session, session.getOpponent() + " wants a rematch");
        }
        requestMyGames();
    }

    // The opponent left: the room is open again and we own it, so it is no
    // longer a game in progress. On the screen this replaces whatever the
    // overlay was showing (the rematch vote or its "waiting" message). If we
    // already went back to the lobby ourselves there is no session any more
    // and the message is stale.
    private void onOpponentLeft(int id)
    {
        GameSession session = sessions.remove(id);
        requestMyGames();
        refreshWaitingCount();
        if(session == null)
        {
            return;
        }
        if(session != viewed)
        {
            notifyBackground(session, session.getOpponent() + " left the game");
            return;
        }

        displaceNotice();
        overlay.showChoice(
            "Game Over",
            "Your opponent left the room.",
            new String[] {"Leave room"},
            button -> leaveRoom(id)
        );
    }

    // GAME_CLOSED is what the others are told when a game leaves the list. A
    // player of the game is never told it that way, except when the room is
    // deleted under them: the opponent left and the room could not pass to us
    // (docs/protocol.md §8). Then the game is over and the room is gone, so
    // there is nothing to leave.
    private void onGameClosed(int id)
    {
        GameSession session = sessions.remove(id);
        if(session == null)
        {
            sendMessage("LIST_GAMES");
            return;
        }

        requestMyGames();
        refreshWaitingCount();
        if(session != viewed)
        {
            notifyBackground(session, session.getOpponent() + " left the game and the room was closed");
        }
        else
        {
            displaceNotice();
            overlay.showChoice(
                "Game Over",
                "Your opponent left and the room was closed.",
                new String[] {"OK"},
                button ->
                {
                    viewLobby();
                    closeOverlay();
                    sendMessage("LIST_GAMES");
                }
            );
        }
    }

    private void onOpponentStatus(int id, String status)
    {
        GameSession session = sessions.get(id);
        if(session == null)
        {
            return;
        }

        session.setOpponentAway(status.equals("AWAY"));
        if(session == viewed)
        {
            gamePanel.refresh();
        }
        requestMyGames();
    }

    // The games we are playing: one row each for the lobby. What is written in
    // the Status column is what a player wants to know at a glance - whose turn
    // it is, or how a finished game ended.
    private void onMatchList(String[] parts)
    {
        int count = Integer.parseInt(parts[1]);
        List<Object[]> rows = new ArrayList<>();
        int index = 2;

        for(int i = 0; i < count; i++)
        {
            String id = parts[index++];
            String name = NameCodec.decode(parts[index++]);
            String opponent = NameCodec.decode(parts[index++]);
            int myPlayer = Integer.parseInt(parts[index++]);
            String state = parts[index++];
            int turn = Integer.parseInt(parts[index++]);
            boolean away = parts[index++].equals("AWAY");

            // The list says the same as OPPONENT_STATUS: if a message was
            // missed, this puts the session right.
            GameSession session = sessions.get(Integer.parseInt(id));
            if(session != null)
            {
                session.setOpponentAway(away);
                if(session == viewed)
                {
                    gamePanel.refresh();
                }
            }

            rows.add(new Object[] {id, name, opponent, matchStatus(Integer.parseInt(id), state, turn, myPlayer),
                                   away ? "away" : ""});
        }

        matchRows = rows;
        showMyGames();
    }

    // The rooms we own, in any state. The ones that already have an opponent
    // are in the other list, so only the waiting ones are taken: they have no
    // opponent to show, only a status saying so.
    private void onOwnedList(String[] parts)
    {
        int count = Integer.parseInt(parts[1]);
        List<Object[]> rows = new ArrayList<>();
        int index = 2;

        for(int i = 0; i < count; i++)
        {
            String id = parts[index++];
            String name = NameCodec.decode(parts[index++]);
            String state = parts[index++];

            if(state.equals("WAITING"))
            {
                rows.add(new Object[] {id, name, "", LobbyPanel.WAITING_STATUS, ""});
            }
        }

        waitingRows = rows;
        showMyGames();
    }

    // One table from the two lists, by game id, so a row does not jump around
    // when the room it belongs to changes from waiting to played.
    private void showMyGames()
    {
        List<Object[]> rows = new ArrayList<>(matchRows);
        rows.addAll(waitingRows);
        rows.sort(Comparator.comparingInt(row -> Integer.parseInt(row[0].toString())));

        lobbyPanel.updateMyGames(rows.toArray(new Object[0][]));
    }

    private String matchStatus(int id, String state, int turn, int myPlayer)
    {
        if(state.equals("PLAYING"))
        {
            return (turn == myPlayer) ? "Your turn" : "Opponent's turn";
        }

        // The list does not say how a finished game ended, or what the players
        // decided about the rematch: the session does.
        GameSession session = sessions.get(id);
        if(session != null && session.iWantRematch())
        {
            return "Waiting for rematch";
        }
        if(session != null && session.opponentWantsRematch())
        {
            return "Rematch requested";
        }

        String result = (session != null) ? session.getResult() : null;
        if("WIN".equals(result))
        {
            return "You won";
        }
        if("LOSE".equals(result))
        {
            return "You lost";
        }
        return "DRAW".equals(result) ? "Draw" : "Finished";
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
                    requestMyGames();
                }
            }
        );
    }

    // The player leaves the board but not the game: it stays as it is, in the
    // list of their games, and takes no moves until they come back. Telling the
    // server there is no active game is also what tells the opponent, in every
    // other game, that the player is no longer busy elsewhere.
    public void goHome()
    {
        viewLobby();
        sendMessage("SET_ACTIVE_GAME 0");
        closeOverlay();
    }

    // Back to a game of the list: the board is as the last message left it,
    // with what happened while the player was away. A finished game asks again
    // what to do about the rematch.
    public void resumeGame(int id)
    {
        GameSession session = sessions.get(id);
        if(session == null)
        {
            requestMyGames(); // the list was out of date
            return;
        }

        viewGame(session);
        if(session.getResult() != null)
        {
            showGameOver(session);
        }
    }

    // Abandoning a game in the middle gives the win to nobody: the opponent
    // stays alone in the room, which is waiting for players again. Going home
    // is the way to leave the board without giving the game up.
    public void askAbandonGame(int gameId)
    {
        overlay.showChoice(
            "Abandon Game",
            "Abandon the game? Nobody wins.",
            new String[] {"Abandon", "Stay"},
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

        // We say which game we are on whenever we show one, so this means our
        // idea of the active game and the server's differ: say it again. The
        // move that was refused is lost, the next click works.
        if(command.equals("MOVE") && code.equals("NOT_ACTIVE"))
        {
            if(viewed != null)
            {
                sendMessage("SET_ACTIVE_GAME " + viewed.getId());
            }
            return;
        }

        // Nobody asked for this one: the front does it by itself. If it fails
        // the game is gone, and a message of its own (OPPONENT_LEFT,
        // GAME_CLOSED) is what tells the player.
        if(command.equals("SET_ACTIVE_GAME"))
        {
            System.out.println("Could not make the game active: " + code);
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
            case "TOO_MANY_MATCHES": return "You are already playing the maximum number of games (5). Leave one first.";
            case "JOINER_FULL":     return "That player is already playing the maximum number of games (5).";
            case "NO_PENDING":      return "That request is no longer valid.";
            default:                return "Unexpected server error (" + command + ": " + code + ").";
        }
    }

    // The room survives the end of the game, so the player has to choose:
    // vote for a rematch (it starts only if the opponent votes too) or leave
    // the room. After voting the same box says the player is waiting: it is
    // closed by the GAME_START of the rematch; a vote can't be withdrawn, so
    // leaving stays the only way out. The box is drawn from what the session
    // knows, so it comes out the same whenever it is drawn again (the opponent
    // votes first, or the player comes back to the game later).
    private void showGameOver(GameSession session)
    {
        displaceNotice();

        int id = session.getId();

        if(session.iWantRematch())
        {
            overlay.showChoice(
                "Game Over",
                "Waiting for the opponent's decision...",
                new String[] {"Leave room", "Home"},
                choice ->
                {
                    if(choice == 0)
                    {
                        leaveRoom(id);
                    }
                    else
                    {
                        goHome();
                    }
                }
            );
            return;
        }

        String message;
        if(session.getResult().equals("WIN"))
        {
            message = "You won!";
        }
        else if(session.getResult().equals("LOSE"))
        {
            message = "You lost.";
        }
        else
        {
            message = "It's a draw.";
        }

        if(session.opponentWantsRematch())
        {
            message += " Your opponent wants a rematch.";
        }

        overlay.showChoice(
            "Game Over",
            message,
            new String[] {"Rematch", "Leave room", "Home"},
            choice ->
            {
                if(choice == 0)
                {
                    session.setIWantRematch(true);
                    sendMessage("REMATCH " + id);
                    showGameOver(session);
                }
                else if(choice == 1)
                {
                    leaveRoom(id);
                }
                else
                {
                    goHome();
                }
            }
        );
    }

    // The player is out of the game: it is not theirs any more, and the room
    // goes back to waiting (or is deleted, if nobody else is in it).
    private void leaveRoom(int gameId)
    {
        sessions.remove(gameId);

        // LEAVE_GAME goes first: the list of our games asked for by viewLobby
        // must not still have this game in it.
        sendMessage("LEAVE_GAME " + gameId);

        // Lobby before closing the box: closing it may bring up a join request
        // that was waiting for the lobby.
        if(viewed != null && viewed.getId() == gameId)
        {
            viewLobby();
        }
        refreshWaitingCount();
        closeOverlay();
        sendMessage("LIST_GAMES");
    }

    public void sendMessage(String msg)
    {
        if(out != null)
        {
            out.println(msg);
        }
    }

    public String getUsername()
    {
        return username;
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