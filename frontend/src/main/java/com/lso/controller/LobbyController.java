package com.lso.controller;

import java.util.ArrayList;
import java.util.List;

import com.lso.GameSession;
import com.lso.NameCodec;
import com.lso.ServerConnection;
import com.lso.view.Dialogs;
import com.lso.view.ErrorText;
import com.lso.view.LobbyPanel;
import com.lso.view.OverlayPanel.Choice;

// The lobby and what comes before it: the username, the two lists (the rooms
// to join and our own rooms and games), creating and deleting a room.
public class LobbyController
{
    private final ServerConnection connection;
    private final Dialogs dialogs;
    private final LobbyPanel lobbyPanel;
    private final GameController games;

    private String username;

    // What the player last typed as username and as room name, to give it
    // back when the server refuses it.
    private String typedUsername = "";
    private String typedRoomName = "";

    // How many rooms and games we have, as the last MY_GAME_LIST said (see
    // MainController.MAX_MATCHES).
    private int myGamesCount;

    LobbyController(ServerConnection connection, Dialogs dialogs, LobbyPanel lobbyPanel, GameController games)
    {
        this.connection = connection;
        this.dialogs = dialogs;
        this.lobbyPanel = lobbyPanel;
        this.games = games;
    }

    String username()
    {
        return username;
    }

    int myGamesCount()
    {
        return myGamesCount;
    }

    void onUsernameSet(String name)
    {
        username = name;
        lobbyPanel.setUsername(username);
        dialogs.close();
        connection.send("LIST_GAMES");
    }

    // OK pressed twice before the answer sends the name twice: the first one
    // was accepted, ALREADY_NAMED is the refusal of the second.
    void onUsernameRefused(String code)
    {
        if(!code.equals("ALREADY_NAMED"))
        {
            askUsername(ErrorText.of(code) + " Choose another one:");
        }
    }

    // A room name the server does not accept: the box comes back with the
    // name, to be corrected. It may take the place of a notice that appeared
    // when the box closed, which then waits its turn again.
    void onRoomNameRefused(String code)
    {
        askRoomName(ErrorText.of(code) + " Choose another one:");
    }

    // The rooms we can ask to join.
    void onGameList(String[] parts)
    {
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
    }

    // Every room and game of ours, by id. A room that waits has "-" as
    // opponent, which is not read: it could also be the name of a real opponent.
    void onMyGameList(String[] parts)
    {
        int count = Integer.parseInt(parts[1]);
        List<Object[]> rows = new ArrayList<>();
        int index = 2;
        myGamesCount = count;

        for(int i = 0; i < count; i++)
        {
            String id = parts[index++];
            String name = NameCodec.decode(parts[index++]);
            String opponent = NameCodec.decode(parts[index++]);
            int myPlayer = Integer.parseInt(parts[index++]);
            String state = parts[index++];
            int turn = Integer.parseInt(parts[index++]);
            boolean away = parts[index++].equals("AWAY");

            if(!state.equals("WAITING"))
            {
                games.setOpponentAway(Integer.parseInt(id), away);
                rows.add(new Object[] {id, name, opponent, matchStatus(Integer.parseInt(id), state, turn, myPlayer), away ? "away" : ""});
            }
            else
            {
                rows.add(new Object[] {id, name, "", LobbyPanel.WAITING_STATUS, ""});
            }
        }

        lobbyPanel.updateMyGames(rows.toArray(new Object[0][]));
    }

    private String matchStatus(int id, String state, int turn, int myPlayer)
    {
        if(state.equals("PLAYING"))
        {
            return (turn == myPlayer) ? LobbyPanel.YOUR_TURN : "Opponent's turn";
        }

        // The list does not say how a finished game ended, or what the players
        // decided about the rematch: the session does.
        GameSession session = games.session(id);
        return (session != null) ? session.finishedText() : "Finished";
    }

    // The server refuses every command until a username is set, so this is
    // the first thing the client does. Quit closes the app.
    void askUsername(String prompt)
    {
        dialogs.ask(
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
                connection.send("SET_USERNAME " + NameCodec.encode(typedUsername));
            },
            new Choice("Quit", () -> System.exit(0))
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
        dialogs.ask(
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
                dialogs.close();
                connection.send("CREATE_GAME " + NameCodec.encode(typedRoomName));
            },
            new Choice("Cancel", dialogs::close)
        );
    }

    // The creator leaving a waiting room alone is how a room is deleted
    // (docs/protocol.md §7). The others are told with GAME_CLOSED but the
    // sender only gets GAME_LEFT, so the list is asked again.
    public void askDeleteRoom(String gameId, String roomName)
    {
        dialogs.show("Delete Room", "Delete \"" + roomName + "\"?",
            new Choice("Delete", () ->
            {
                dialogs.close();
                connection.send("LEAVE_GAME " + gameId);
                connection.send("LIST_GAMES");
                connection.send("LIST_MY_GAMES");
            }),
            new Choice("Cancel", dialogs::close));
    }
}
