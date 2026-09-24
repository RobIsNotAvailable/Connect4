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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import com.lso.view.MainFrame;
import com.lso.view.GamePanel;
import com.lso.view.LobbyPanel;
import com.lso.view.OverlayPanel;
import com.lso.view.UiUtil;

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

    // Things that interrupt the player (a join request, an error) must not
    // replace what the overlay is already showing: a Game Over box swallowed
    // by a notice would leave the player without Rematch / Leave room. So a
    // notice waits in this queue until the overlay is free.
    private final Deque<Notice> pendingNotices = new ArrayDeque<>();
    private Notice shownNotice;

    // The rooms we asked to join, by id, while their owners decide. The answer
    // (JOIN_RESULT) and the room closing (GAME_CLOSED) name only the id, and
    // the player must be told which room it was. 'lastAsked' is the last
    // JOIN_GAME sent: a refusal (ERROR JOIN_GAME) names no room, but it comes
    // back at once, before the player can ask for another one.
    private final Map<Integer, AskedRoom> askedRooms = new LinkedHashMap<>();
    private int lastAsked;

    // What the player last typed as username and as room name, to give it
    // back when the server refuses it.
    private String typedUsername = "";
    private String typedRoomName = "";

    // MAX_GAMES_PER_PLAYER in the server: the games a player can be in at once.
    private static final int MAX_MATCHES = 5;

    private LobbyPanel lobbyPanel;
    private JTabbedPane gamesTabs;
    private Map<Integer, GamePanel> activeGamePanels;
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

    private record AskedRoom(String name, String owner) {}

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

        activeGamePanels = new HashMap<>();
        gamesTabs = new JTabbedPane();
        mainPanel.add(gamesTabs, "GamesContainer");

        gamesTabs.addChangeListener(e -> 
        {
            GamePanel selected = (GamePanel) gamesTabs.getSelectedComponent();
            if(selected != null)
            {
                for(Map.Entry<Integer, GamePanel> entry : activeGamePanels.entrySet())
                {
                    if(entry.getValue() == selected)
                    {
                        sendMessage("SET_ACTIVE_GAME " + entry.getKey());
                        break;
                    }
                }
            }
        });
        
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
        GamePanel gp = activeGamePanels.get(session.getId());
        if(gp != null)
        {
            gamesTabs.setSelectedComponent(gp);
        }
        cardLayout.show(mainPanel, "GamesContainer");
        sendMessage("SET_ACTIVE_GAME " + session.getId());
    }

    private void viewLobby()
    {
        viewed = null;
        cardLayout.show(mainPanel, "Lobby");
        sendMessage("LIST_MY_GAMES");
    }

    // Something happened in a game that is not on screen, and the player is on
    // another board: a line on that board says so. In the lobby there is no
    // need, the list of the games shows it.
    private void notifyBackground(GameSession session, String text)
    {
        if(viewed != null && session != viewed)
        {
            GamePanel gp = activeGamePanels.get(session.getId());
            if(gp != null)
            {
                gp.showNotification(text);
            }
        }
    }

    private static String resultText(String result)
    {
        if(result.equals("WIN"))
        {
            return "you won";
        }
        return result.equals("LOSE") ? "you lost" : "draw";
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

            case "MY_GAME_LIST":
                onMyGameList(parts);
                break;

            case "GAME_CREATED":
                sendMessage("LIST_GAMES");
                sendMessage("LIST_MY_GAMES");
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
                onJoinResult(Integer.parseInt(parts[1]), parts[2].equals("1"));
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
        askedRooms.remove(id);
        showJoinStatus();

        GameSession session = new GameSession(id, myPlayer, username, opponent);
        sessions.put(id, session);

        if(!activeGamePanels.containsKey(id))
        {
            GamePanel newGp = new GamePanel(this);
            newGp.show(session);
            activeGamePanels.put(id, newGp);
            gamesTabs.addTab("VS " + opponent, newGp);
        }

        if(viewed == null || viewed.getId() == id)
        {
            displaceNotice();
            viewGame(session);
            closeOverlay();
        }
        else
        {
            sendMessage("SET_ACTIVE_GAME " + viewed.getId());
            notifyBackground(session, "New game against " + opponent);
        }
    }

    // Join Selected. The server answers only when the owner decides, so the
    // status line says we are waiting. A room already asked is not asked again
    // (a double click): the server would refuse it.
    public void joinGame(int id, String roomName, String owner)
    {
        if(askedRooms.containsKey(id))
        {
            return;
        }
        askedRooms.put(id, new AskedRoom(roomName, owner));
        lastAsked = id;
        sendMessage("JOIN_GAME " + id);
        showJoinStatus();
    }

    // The status line under the lobby says which requests are still waiting.
    private void showJoinStatus()
    {
        if(askedRooms.isEmpty())
        {
            lobbyPanel.clearStatus();
        }
        else if(askedRooms.size() == 1)
        {
            AskedRoom room = askedRooms.values().iterator().next();
            lobbyPanel.setStatus("Waiting for " + room.owner() + " to accept your request...");
        }
        else
        {
            lobbyPanel.setStatus("Waiting for " + askedRooms.size() + " owners to accept your requests...");
        }
    }

    // The owner decided. On accept GAME_START follows, which is all the player
    // needs to see. A request can also end without the owner declining it: the
    // server cancels it when we reach MAX_MATCHES while it waits, and then
    // nobody could accept it anyway.
    private void onJoinResult(int id, boolean accepted)
    {
        AskedRoom room = askedRooms.remove(id);
        showJoinStatus();
        if(accepted)
        {
            return;
        }

        String roomName = (room != null) ? "\"" + room.name() + "\"" : "the room";
        if(sessions.size() >= MAX_MATCHES)
        {
            showNotice("Request cancelled", "Your request to join " + roomName
                       + " did not go through: you are already playing the maximum number of games (" + MAX_MATCHES + ").");
        }
        else if(room != null)
        {
            showNotice("Request declined", room.owner() + " declined your request to join " + roomName + ".");
        }
        else
        {
            showNotice("Request declined", "Your request to join the room was declined.");
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

        GamePanel gp = activeGamePanels.get(id);
        if(gp != null)
        {
            gp.refresh();
            int tabIndex = gamesTabs.indexOfComponent(gp);
            if(tabIndex != -1)
            {
                boolean isMyTurn = (turn == session.getMyPlayer());
                if(isMyTurn)
                {
                    gamesTabs.setIconAt(tabIndex, new UiUtil.DotIcon(UiUtil.SUCCESS_GREEN, 12));
                }
                else
                {
                    gamesTabs.setIconAt(tabIndex, new UiUtil.DotIcon(UiUtil.BACKGROUND_GRAY, 12));
                }
            }
        }

        if(!wasMyTurn && session.getTurn() == session.getMyPlayer())
        {
            notifyBackground(session, "Your turn against " + session.getOpponent());
        }
        sendMessage("LIST_MY_GAMES");
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
        sendMessage("LIST_MY_GAMES");
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
        sendMessage("LIST_MY_GAMES");
    }

    // The opponent left: the room is open again and we own it, so it is no
    // longer a game in progress. On the screen this replaces whatever the
    // overlay was showing (the rematch vote or its "waiting" message). If we
    // already went back to the lobby ourselves there is no session any more
    // and the message is stale.
    private void onOpponentLeft(int id)
    {
        GameSession session = sessions.remove(id);
        sendMessage("LIST_MY_GAMES");
        if(session == null)
        {
            return;
        }
        if(session != viewed)
        {
            notifyBackground(session, session.getOpponent() + " left the game");
            return;
        }

        // The room is ours and waits for players again, as it does when this
        // happens to a game that is not on screen: Home keeps it that way,
        // Leave room deletes it.
        displaceNotice();
        overlay.showChoice(
            "Game Over",
            "Your opponent left the room, you'll be redirected to the home screen. Delete the room?",
            new String[] {"Yes, delete it", "No, keep it"},
            button ->
            {
                if(button == 0)
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

    // GAME_CLOSED is what the others are told when a game leaves the list. A
    // player of the game is never told it that way, except when the room is
    // deleted under them: the opponent left and the room could not pass to us
    // (docs/protocol.md §8). Then the game is over and the room is gone, so
    // there is nothing to leave.
    private void onGameClosed(int id)
    {
        // A room we asked to join, deleted before its owner answered.
        AskedRoom asked = askedRooms.remove(id);
        if(asked != null)
        {
            showJoinStatus();
            showNotice("Room closed", "\"" + asked.name() + "\" was closed before " + asked.owner() + " answered your request.");
        }

        GameSession session = sessions.remove(id);
        if(session == null)
        {
            sendMessage("LIST_GAMES");
            return;
        }

        sendMessage("LIST_MY_GAMES");
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
        GamePanel gp = activeGamePanels.get(id);
        if(gp != null)
        {
            gp.refresh();
        }
        sendMessage("LIST_MY_GAMES");
    }

    private void onMyGameList(String[] parts)
    {
        int count = Integer.parseInt(parts[1]);
        List<Object[]> rows = new ArrayList<>();
        int index = 2;

        for(int i = 0; i < count; i++)
        {
            String id = parts[index++];
            String name = NameCodec.decode(parts[index++]);
            String opponentRaw = parts[index++];
            String opponent = opponentRaw.equals("-") ? "" : NameCodec.decode(opponentRaw);
            int myPlayer = Integer.parseInt(parts[index++]);
            String state = parts[index++];
            int turn = Integer.parseInt(parts[index++]);
            boolean away = parts[index++].equals("AWAY");

            if(!state.equals("WAITING"))
            {
                GameSession session = sessions.get(Integer.parseInt(id));
                if(session != null)
                {
                    session.setOpponentAway(away);
                    GamePanel gp = activeGamePanels.get(session.getId());
                    if(gp != null)
                    {
                        gp.refresh();
                    }
                }
                rows.add(new Object[] {id, name, opponent, matchStatus(Integer.parseInt(id), state, turn, myPlayer), away ? "away" : ""});
            }
            else
            {
                rows.add(new Object[] {id, name, "", LobbyPanel.WAITING_STATUS, ""});
            }
        }

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
            typedUsername,
            "OK",
            name ->
            {
                // Refused here, as the room name is: the server would only
                // answer that the arguments are wrong.
                if(name.trim().isEmpty())
                {
                    typedUsername = "";
                    askUsername("Please type a username:");
                    return;
                }
                typedUsername = name.trim();
                sendMessage("SET_USERNAME " + NameCodec.encode(typedUsername));
            },
            "Quit",
            () -> System.exit(0)
        );
    }

    // Same overlay as the username, so it stays inside the window. An empty
    // name keeps it open and says so, Cancel closes it.
    public void askRoomName()
    {
        typedRoomName = "";
        askRoomName("Room name:");
    }

    // Asked again, with what was typed, when the name is empty or the server
    // refuses it.
    private void askRoomName(String prompt)
    {
        overlay.showInput(
            "Create Game",
            prompt,
            typedRoomName,
            "OK",
            name ->
            {
                if(name.trim().isEmpty())
                {
                    typedRoomName = "";
                    askRoomName("Please type a name for the room:");
                    return;
                }
                typedRoomName = name.trim();
                closeOverlay();
                sendMessage("CREATE_GAME " + NameCodec.encode(typedRoomName));
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
                    sendMessage("LIST_MY_GAMES");
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
            sendMessage("LIST_MY_GAMES");
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
            "Abandon the game?",
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
            // OK pressed twice before the answer sends the name twice: the
            // first one was accepted, this is the refusal of the second.
            if(code.equals("ALREADY_NAMED"))
            {
                return;
            }
            askUsername(errorText(code) + " Choose another one:");
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

        // The buttons already refuse a move that is not allowed, so the other
        // refusals can only come from a race: with a GAME_STATE that is about
        // to arrive (the board on screen will be right in a moment), or with
        // the opponent leaving, which OPPONENT_LEFT or GAME_CLOSED tells.
        // The same goes for a rematch voted as the opponent leaves or voted
        // twice, and for leaving a game we are already out of.
        if(command.equals("MOVE") || command.equals("REMATCH") || command.equals("LEAVE_GAME"))
        {
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

        // A room name the server does not accept: the box comes back with the
        // name, to be corrected. It may take the place of a notice that
        // appeared when the box closed, which then waits its turn again.
        if(command.equals("CREATE_GAME") && code.equals("INVALID_NAME"))
        {
            displaceNotice();
            askRoomName(errorText(code) + " Choose another one:");
            return;
        }

        // Refused at once, so it is the request just sent. ALREADY_PENDING
        // is someone else's request: ours are never sent twice (joinGame).
        if(command.equals("JOIN_GAME"))
        {
            askedRooms.remove(lastAsked);
            showJoinStatus();
        }

        showNotice("Error", errorText(code));
    }

    // What the player reads: never the code itself, which is already in the
    // "Received:" line on the console.
    private static String errorText(String code)
    {
        switch(code)
        {
            case "NOT_FOUND":       return "That room no longer exists.";
            case "NOT_WAITING":     return "That room is no longer waiting for players.";
            case "SELF_JOIN":       return "You can't join your own room.";
            // A room takes one request at a time: this one may be someone else's.
            case "ALREADY_PENDING": return "Someone is already waiting to join this room. Try again in a moment.";
            case "TOO_MANY_GAMES":  return "You are already playing the maximum number of games (5). Leave one first.";
            case "SERVER_FULL":     return "The server can't host more games right now.";
            case "INVALID_NAME":    return "That name is not valid: use up to 20 letters without accents, digits or symbols.";
            case "USERNAME_TAKEN":  return "That username is already taken.";
            case "JOINER_FULL":     return "That player is already playing the maximum number of games (5), so their request was cancelled.";
            case "NO_PENDING":      return "That player is no longer waiting to join.";
            default:                return "Something went wrong: the server refused the request.";
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
            new String[] {"Rematch", "Leave room"},
            choice ->
            {
                if(choice == 0)
                {
                    session.setIWantRematch(true);
                    sendMessage("REMATCH " + id);
                    showGameOver(session);
                }
                else
                {
                    leaveRoom(id);
                }
            }
        );
    }

    // The player is out of the game: it is not theirs any more, and the room
    // goes back to waiting (or is deleted, if nobody else is in it).
    private void leaveRoom(int gameId)
    {
        sessions.remove(gameId);
        
        GamePanel gp = activeGamePanels.remove(gameId);
        if(gp != null)
        {
            gamesTabs.remove(gp);
        }

        sendMessage("LEAVE_GAME " + gameId);

        if(viewed != null && viewed.getId() == gameId)
        {
            viewLobby();
        }
        closeOverlay();
        sendMessage("LIST_GAMES");
    }

    // A box is on screen. It stops the mouse but not the keys 1-7, which
    // are bound to the whole window.
    public boolean isOverlayOpen()
    {
        return overlay.isVisible();
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