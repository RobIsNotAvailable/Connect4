package com.lso.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import com.lso.ServerConnection;
import com.lso.view.Dialogs;
import com.lso.view.LobbyPanel;
import com.lso.view.OverlayPanel.Choice;

// Requests to join a room, from both sides: the ones we make to the owners of
// other rooms, and the ones others make to join ours.
public class JoinController
{
    private record AskedRoom(String name, String owner) {}

    private final ServerConnection connection;
    private final Dialogs dialogs;
    private final LobbyPanel lobbyPanel;

    // The rooms we asked to join, by id, while their owners decide. The answer
    // (JOIN_RESULT) and the room closing (GAME_CLOSED) name only the id, and
    // the player must be told which room it was. 'lastAsked' is the last
    // JOIN_GAME sent: a refusal (ERROR JOIN_GAME) names no room, but it comes
    // back at once, before the player can ask for another one.
    private final Map<Integer, AskedRoom> askedRooms = new LinkedHashMap<>();
    private int lastAsked;

    JoinController(ServerConnection connection, Dialogs dialogs, LobbyPanel lobbyPanel)
    {
        this.connection = connection;
        this.dialogs = dialogs;
        this.lobbyPanel = lobbyPanel;
    }

    // Join Room. The server answers only when the owner decides, so the
    // status line says we are waiting. A room already asked is not asked again
    // (a double click): the server would refuse it.
    public void join(int id, String roomName, String owner)
    {
        if(askedRooms.containsKey(id))
        {
            return;
        }
        askedRooms.put(id, new AskedRoom(roomName, owner));
        lastAsked = id;
        connection.send("JOIN_GAME " + id);
        showStatus();
    }

    // The owner decided. On accept GAME_START follows, which is all the player
    // needs to see. A request can also end without the owner declining it: the
    // server cancels it when we reach MAX_MATCHES while it waits ('gamesCount'
    // is how many we have), and then nobody could accept it anyway.
    void onResult(int id, boolean accepted, int gamesCount)
    {
        AskedRoom room = askedRooms.remove(id);
        showStatus();
        if(accepted)
        {
            return;
        }

        String roomName = (room != null) ? "\"" + room.name() + "\"" : "the room";
        if(gamesCount >= MainController.MAX_MATCHES)
        {
            dialogs.notice("Request Cancelled", "Your request to join " + roomName
                           + " did not go through: you already have the maximum number of games (" + MainController.MAX_MATCHES + ").");
        }
        else if(room != null)
        {
            dialogs.notice("Request Declined", room.owner() + " declined your request to join " + roomName + ".");
        }
        else
        {
            dialogs.notice("Request Declined", "Your request to join the room was declined.");
        }
    }

    // A game starts in the room: if we asked for it, the request is over.
    void onGameStart(int id)
    {
        askedRooms.remove(id);
        showStatus();
    }

    // A room we asked to join, deleted before its owner answered.
    void onRoomClosed(int id)
    {
        AskedRoom asked = askedRooms.remove(id);
        if(asked != null)
        {
            showStatus();
            dialogs.notice("Room Closed", "\"" + asked.name() + "\" was closed before " + asked.owner() + " answered your request.");
        }
    }

    // ERROR JOIN_GAME: refused at once, so it is the request just sent.
    // ALREADY_PENDING is someone else's request: ours are never sent twice.
    void onRefused()
    {
        askedRooms.remove(lastAsked);
        showStatus();
    }

    // A player asks to join a room of ours. Both answers go to the server,
    // which starts the game on Accept.
    void ask(String gameId, String joiner)
    {
        dialogs.joinRequest(gameId, "Join Request", joiner + " wants to join your room. Accept?",
            new Choice("Accept", () -> answer(gameId, 1)),
            new Choice("Decline", () -> answer(gameId, 0)));
    }

    private void answer(String gameId, int accepted)
    {
        connection.send("JOIN_RESPONSE " + gameId + " " + accepted);
        dialogs.close();
    }

    // The status line under the lobby says which requests are still waiting.
    private void showStatus()
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
}
