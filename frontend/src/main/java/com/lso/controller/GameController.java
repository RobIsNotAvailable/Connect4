package com.lso.controller;

import java.util.HashMap;
import java.util.Map;

import javax.swing.JComponent;

import com.lso.GameSession;
import com.lso.MainController;
import com.lso.ServerConnection;
import com.lso.view.Dialogs;
import com.lso.view.GameTabs;

// The games we play, one tab each on the game screen, and the one on screen.
// Every message of the server about a game names it by id and reaches it
// here, whichever game the player is looking at.
public class GameController
{
    private final MainController controller;
    private final ServerConnection connection;
    private final Dialogs dialogs;
    private final GameTabs tabs;
    private final GameEndController ends;

    // The games we are in, by id: a player can be in several at once. The tab
    // of a game (its GamePanel) can outlive it for a moment: when the opponent
    // leaves the game on screen, the tab stays under the box that says so.
    // 'viewed' is the game on the game screen, or null while the lobby is shown.
    private final Map<Integer, GameSession> sessions = new HashMap<>();
    private GameSession viewed;

    public GameController(MainController controller, ServerConnection connection, Dialogs dialogs)
    {
        this.controller = controller;
        this.connection = connection;
        this.dialogs = dialogs;
        ends = new GameEndController(this, connection, dialogs);
        tabs = new GameTabs(this, ends);
        tabs.addChangeListener(e -> onTabSelected());
    }

    // The end of the games: the result, the rematch, leaving them.
    public GameEndController ends()
    {
        return ends;
    }

    // The game screen: the tabs of the games.
    public JComponent screen()
    {
        return tabs;
    }

    public boolean isViewing()
    {
        return viewed != null;
    }

    // A game of ours, or null.
    GameSession session(int id)
    {
        return sessions.get(id);
    }

    // A game starts: an accepted join, or a rematch, which reuses the id of the
    // game and so replaces its session. The game takes the screen only if
    // nothing else is on it (or if it is the game on it already): a player in
    // the middle of another game must not be pulled out of it.
    public void onGameStart(int id, int myPlayer, String myName, String opponent)
    {
        GameSession session = new GameSession(id, myPlayer, myName, opponent);
        sessions.put(id, session);
        tabs.showGame(session);

        if(viewed == null || viewed.getId() == id)
        {
            dialogs.displace();
            viewGame(session);
            dialogs.close();
        }
        else
        {
            connection.send("SET_ACTIVE_GAME " + viewed.getId());
            notifyBackground(session, "New game against " + opponent);
        }
    }

    // A message can name a game we no longer have: we left it while the
    // message was on its way. It is ignored, like the ones below.
    public void onGameState(int id, int turn, String board)
    {
        GameSession session = sessions.get(id);
        if(session == null)
        {
            return;
        }

        boolean wasMyTurn = (session.getTurn() == session.getMyPlayer());
        session.update(turn, board);
        boolean isMyTurn = (turn == session.getMyPlayer());

        tabs.panelOf(id).refresh();
        tabs.setMyTurn(id, isMyTurn);

        if(!wasMyTurn && isMyTurn)
        {
            notifyBackground(session, "Your turn against " + session.getOpponent());
        }
        connection.send("LIST_MY_GAMES");
    }

    public void onOpponentStatus(int id, boolean away)
    {
        if(sessions.containsKey(id))
        {
            setOpponentAway(id, away);
            connection.send("LIST_MY_GAMES");
        }
    }

    // The opponent is in this game or not (OPPONENT_STATUS, and the list of our
    // games says the same).
    void setOpponentAway(int id, boolean away)
    {
        GameSession session = sessions.get(id);
        if(session != null)
        {
            session.setOpponentAway(away);
            tabs.panelOf(id).refresh();
        }
    }

    // A move on the board on screen. The keys 1-7 reach the board even while a
    // box is open over it (the box stops only the mouse): the box is answered
    // first.
    public void move(int id, int column)
    {
        if(!dialogs.isOpen())
        {
            connection.send("MOVE " + id + " " + column);
        }
    }

    // The bell of the boards: how many requests to join our rooms wait, and a
    // click shows the first one even during a game.
    public void setWaitingRequests(int count)
    {
        tabs.setWaitingRequests(count);
    }

    public void showJoinRequest()
    {
        dialogs.showJoinRequest();
    }

    // ERROR MOVE NOT_ACTIVE. We say which game we are on whenever we show one,
    // so this means our idea of the active game and the server's differ: say it
    // again. The move that was refused is lost, the next click works.
    public void onNotActive()
    {
        if(viewed != null)
        {
            connection.send("SET_ACTIVE_GAME " + viewed.getId());
        }
    }

    // The player leaves the board but not the game: it stays as it is, in the
    // list of their games, and takes no moves until they come back. Telling the
    // server there is no active game is also what tells the opponent, in every
    // other game, that the player is no longer busy elsewhere.
    public void goHome()
    {
        viewLobby();
        connection.send("SET_ACTIVE_GAME 0");
        dialogs.close();
    }

    // Back to a game of the list: the board is as the last message left it,
    // with what happened while the player was away. A finished game asks again
    // what to do about the rematch.
    public void resumeGame(int id)
    {
        GameSession session = sessions.get(id);
        if(session == null)
        {
            connection.send("LIST_MY_GAMES");
            return;
        }

        viewGame(session);
        if(session.getResult() != null)
        {
            ends.showGameOver(session);
        }
    }

    // Puts a game on the game screen. The server already made it the active
    // game if the player had nothing else to do; saying so anyway costs
    // nothing (it is a no-op then) and keeps client and server agreeing about
    // which game takes moves.
    private void viewGame(GameSession session)
    {
        viewed = session;
        tabs.select(session.getId());
        controller.showGames();
        connection.send("SET_ACTIVE_GAME " + session.getId());
    }

    private void viewLobby()
    {
        viewed = null;
        controller.showLobby();
        connection.send("LIST_MY_GAMES");
    }

    // A tab picked by the player works like Resume: that game takes the screen
    // and becomes the active one. The tabs the code selects go through
    // viewGame, which sets 'viewed' first, and what happens to the tabs while
    // the lobby is shown does not matter.
    private void onTabSelected()
    {
        GameSession selected = tabs.selectedGame();
        if(viewed != null && selected != null && selected != viewed)
        {
            resumeGame(selected.getId());
        }
    }

    boolean isOnScreen(GameSession session)
    {
        return session == viewed;
    }

    // We are no longer in game 'id', but its tab stays until removeTab (under
    // the box that says the opponent left). Returns the game, or null.
    GameSession forget(int id)
    {
        return sessions.remove(id);
    }

    void removeTab(int id)
    {
        tabs.removeGame(id);
    }

    // We are out of game 'id': it goes, with its tab. If it is on screen the
    // lobby is shown first, or the tabs would put another game in its place.
    void close(int id)
    {
        sessions.remove(id);
        if(viewed != null && viewed.getId() == id)
        {
            viewLobby();
        }
        tabs.removeGame(id);
    }

    // Something happened in a game that is not on screen, and the player is on
    // another board: a line on that board says so. In the lobby there is no
    // need, the list of the games shows it.
    void notifyBackground(GameSession session, String text)
    {
        if(viewed != null && session != viewed)
        {
            tabs.panelOf(viewed.getId()).showNotification(text);
        }
    }
}
