package com.lso.controller;

import com.lso.GameSession;
import com.lso.ServerConnection;
import com.lso.view.Dialogs;
import com.lso.view.OverlayPanel.Choice;

// How a game ends for us: the result, the vote for a rematch, the opponent who
// leaves, and our leaving the room (after the game, or abandoning it in the
// middle). The games themselves, and which one is on screen, are in
// GameController.
public class GameEndController
{
    private final GameController games;
    private final ServerConnection connection;
    private final Dialogs dialogs;

    GameEndController(GameController games, ServerConnection connection, Dialogs dialogs)
    {
        this.games = games;
        this.connection = connection;
        this.dialogs = dialogs;
    }

    // What the player does at the end of a game is asked only on the game they
    // are looking at. The result is kept in the session for the others.
    void onGameOver(int id, GameSession.Result result)
    {
        GameSession session = games.session(id);
        if(session == null)
        {
            return;
        }

        session.setResult(result);
        if(games.isOnScreen(session))
        {
            showGameOver(session);
        }
        else
        {
            games.notifyBackground(session, "Game against " + session.getOpponent() + " is over: " + result.text.toLowerCase());
        }
        connection.send("LIST_MY_GAMES");
    }

    // The opponent voted for a rematch first: the same box is drawn again, now
    // saying so. It only arrives to a player who has not voted yet.
    void onRematchNotify(int id)
    {
        GameSession session = games.session(id);
        if(session == null)
        {
            return;
        }

        session.setOpponentWantsRematch(true);
        if(games.isOnScreen(session))
        {
            showGameOver(session);
        }
        else
        {
            games.notifyBackground(session, session.getOpponent() + " wants a rematch");
        }
        connection.send("LIST_MY_GAMES");
    }

    // The opponent left: the room is open again and we own it, so it is no
    // longer a game in progress. On the screen this replaces whatever the
    // overlay was showing (the rematch vote or its "waiting" message). If we
    // already went back to the lobby ourselves there is no session any more
    // and the message is stale.
    void onOpponentLeft(int id)
    {
        GameSession session = games.forget(id);
        connection.send("LIST_MY_GAMES");
        if(session == null)
        {
            return;
        }
        if(!games.isOnScreen(session))
        {
            games.notifyBackground(session, session.getOpponent() + " left the game");
            games.removeTab(id);
            return;
        }

        // The room is ours and waits for players again, as it does when this
        // happens to a game that is not on screen: keeping it goes home,
        // deleting it leaves the room.
        dialogs.show("Game Over", "Your opponent left the room, you'll be redirected to the home screen. Delete the room?",
            new Choice("Yes, delete it", () -> leaveRoom(id)),
            new Choice("No, keep it", () ->
            {
                games.goHome();
                games.removeTab(id);
            }));
    }

    // Abandoning a game in the middle gives the win to nobody: the opponent
    // stays alone in the room, which is waiting for players again. Going home
    // is the way to leave the board without giving the game up.
    public void askAbandonGame(int id)
    {
        dialogs.show("Abandon Game", "Abandon the game?",
            new Choice("Abandon", () -> leaveRoom(id)),
            new Choice("Stay", dialogs::close));
    }

    // The room survives the end of the game, so the player has to choose:
    // vote for a rematch (it starts only if the opponent votes too) or leave
    // the room. After voting the same box says the player is waiting: it is
    // closed by the GAME_START of the rematch; a vote can't be withdrawn, so
    // leaving stays the only way out. The box is drawn from what the session
    // knows, so it comes out the same whenever it is drawn again (the opponent
    // votes first, or the player comes back to the game later).
    void showGameOver(GameSession session)
    {
        int id = session.getId();

        if(session.iWantRematch())
        {
            dialogs.show("Game Over", "Waiting for the opponent's decision...",
                new Choice("Leave room", () -> leaveRoom(id)),
                new Choice("Home", games::goHome));
            return;
        }

        String message = session.getResult().text + ".";
        if(session.opponentWantsRematch())
        {
            message += " Your opponent wants a rematch.";
        }

        dialogs.show("Game Over", message,
            new Choice("Rematch", () ->
            {
                session.setIWantRematch(true);
                connection.send("REMATCH " + id);
                showGameOver(session);
            }),
            new Choice("Leave room", () -> leaveRoom(id)));
    }

    // The player is out of the game: it is not theirs any more, and the room
    // goes back to waiting (or is deleted, if nobody else is in it).
    private void leaveRoom(int id)
    {
        // LEAVE_GAME goes first: the list of our games asked for when the
        // lobby is shown must not still have this game in it.
        connection.send("LEAVE_GAME " + id);
        games.close(id);
        dialogs.close();
        connection.send("LIST_GAMES");
    }
}
