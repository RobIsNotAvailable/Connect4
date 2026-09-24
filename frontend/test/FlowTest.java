import com.lso.view.OverlayPanel;

import java.util.List;

// Flows of the lobby and of the boxes: the selection in the tables, a join
// request that arrives while a game starts, the guard against double clicks on
// a box that changes, and the tabs of the games.
public class FlowTest extends Rig
{
    public static void main(String[] args) throws Exception
    {
        start();
        logIn("Anna");

        // ---- Available Games keeps its selection across the updates, which
        // arrive whenever anyone creates, joins or leaves a room.

        server("GAME_LIST 2 3 roomA Bob 4 roomB Carl");
        select("gameTable", 1);
        server("GAME_LIST 2 3 roomA Bob 4 roomB Carl");
        check("same list: still selected", selected("gameTable") == 1, "" + selected("gameTable"));
        lobbyButton("Join Selected");
        check("join the room still selected", sent().equals(List.of("JOIN_GAME 4")));
        check("status while waiting", status().equals("Waiting for Carl to accept your request..."), status());
        server("ERROR JOIN_GAME ALREADY_PENDING");
        click("OK");

        server("GAME_LIST 3 3 roomA Bob 5 roomZ Zed 4 roomB Carl");
        check("room moved down: the selection follows it", selected("gameTable") == 2, "" + selected("gameTable"));
        server("GAME_LIST 2 3 roomA Bob 5 roomZ Zed");
        check("room gone: nothing selected", selected("gameTable") == -1, "" + selected("gameTable"));
        lobbyButton("Join Selected");
        check("room gone: Join sends nothing", sent().isEmpty());

        // A room of ours that waits, in My Games: Delete stays enabled across
        // an update, and there is nothing to resume.
        server("MY_GAME_LIST 1 6 mine - 1 WAITING 0 HERE");
        select("myGamesTable", 0);
        check("own room: Delete enabled", lobbyButtonEnabled("Delete Room"));
        server("MY_GAME_LIST 1 6 mine - 1 WAITING 0 HERE");
        check("own room: still selected", selected("myGamesTable") == 0, "" + selected("myGamesTable"));
        check("own room: Delete still enabled", lobbyButtonEnabled("Delete Room"));
        check("own room: nothing to resume", !lobbyButtonEnabled("Resume"));

        // The games in progress too, and an update of the other table does not
        // take the selection away (the two tables select one row between them).
        server("MY_GAME_LIST 3 6 mine - 1 WAITING 0 HERE 8 g8 Bob 1 PLAYING 1 HERE 9 g9 Carl 2 PLAYING 1 HERE");
        select("myGamesTable", 1);
        check("My Games: one row selected in all", selected("myGamesTable") == 1 && selected("gameTable") == -1);
        server("MY_GAME_LIST 3 6 mine - 1 WAITING 0 HERE 8 g8 Bob 1 PLAYING 2 HERE 9 g9 Carl 2 PLAYING 2 HERE");
        check("My Games: still selected", selected("myGamesTable") == 1, "" + selected("myGamesTable"));
        check("My Games: Resume enabled", lobbyButtonEnabled("Resume"));
        server("GAME_LIST 1 3 roomA Bob");
        check("My Games: kept after a GAME_LIST", selected("myGamesTable") == 1 && selected("gameTable") == -1);
        sent();

        // ---- A join request on screen when one of our own requests is
        // accepted: it must not be lost with the box.

        server("JOIN_NOTIFY 7 Dave");
        check("Dave's request", shown() && message().equals("Dave wants to join your game. Accept?"), box());
        server("GAME_START 4 2 Carl");
        server("GAME_STATE 4 1 " + EMPTY_BOARD);
        check("the game is on screen, the request put away", !shown(), box());
        gameButton("Home");
        check("back in the lobby: Dave's request again", shown() && message().equals("Dave wants to join your game. Accept?"), box());
        sent();
        click("Accept");
        check("the answer reaches the server", sent().contains("JOIN_RESPONSE 7 1"));

        // Erin gives up while we play: her request must not come back.
        server("JOIN_NOTIFY 7 Erin");
        server("GAME_START 10 1 Fred");
        server("GAME_STATE 10 1 " + EMPTY_BOARD);
        check("Erin's request put away", !shown(), box());
        server("JOIN_CANCELLED 7");
        gameButton("Home");
        check("cancelled meanwhile: it does not come back", !shown(), box());

        // An error on screen is not lost either: it comes back over the board.
        server("ERROR JOIN_GAME NOT_FOUND");
        server("GAME_START 11 1 Gina");
        server("GAME_STATE 11 1 " + EMPTY_BOARD);
        check("the error comes back over the board", shown() && message().equals("That room no longer exists."), box());
        click("OK");

        // The Game Over box is closed by the rematch that starts, and the
        // board is the one of the new game.
        server("GAME_STATE 11 0 " + EMPTY_BOARD);
        server("GAME_OVER 11 WIN");
        click("Rematch");
        server("GAME_START 11 1 Gina");
        server("GAME_STATE 11 1 " + EMPTY_BOARD);
        check("rematch: the box closes", !shown(), box());
        sent();
        gameButton("Col 1");
        check("rematch: the new game takes moves", sent().equals(List.of("MOVE 11 0")));

        // ---- A double click on Rematch: the box changes under the mouse,
        // and the second click must not hit "Leave room".

        server("GAME_STATE 11 0 " + EMPTY_BOARD);
        server("GAME_OVER 11 LOSE");
        sent();
        click("Rematch");
        check("double click: the vote is sent", sent().equals(List.of("REMATCH 11")));
        clickNow("Leave room");
        check("double click: the second click is ignored", sent().isEmpty()
              && message().equals("Waiting for the opponent's decision..."), box());
        Thread.sleep(OverlayPanel.CLICK_GUARD_MILLIS + 50);
        clickNow("Leave room");
        check("a moment later the button works", sent().contains("LEAVE_GAME 11"));

        // A box that pops up under a click ignores it too.
        server("JOIN_NOTIFY 12 Hal");
        clickNow("Accept");
        check("a box that just appeared ignores a click", sent().isEmpty() && shown(), box());
        click("Decline");
        sent();

        // ---- The opponent leaves the game on screen: the room is ours and
        // waits for players again. Home keeps it, Leave room deletes it.

        server("GAME_START 13 1 Ivy");
        server("GAME_STATE 13 1 " + EMPTY_BOARD);
        server("OPPONENT_LEFT 13");
        check("opponent left: two choices", shown() && message().equals(
              "Your opponent left the room, you'll be redirected to the home screen. Delete the room?"), box());
        sent();
        click("No, keep it");
        List<String> home = sent();
        check("keep it: the room is kept", !home.contains("LEAVE_GAME 13") && !shown(), home + " / " + box());
        check("keep it: back in the lobby", home.contains("SET_ACTIVE_GAME 0") && home.contains("LIST_MY_GAMES"), home.toString());
        check("keep it: the tab of the game is gone", !tabTitles().contains("VS Ivy"), tabTitles().toString());

        server("GAME_START 13 1 Jay");
        server("GAME_STATE 13 1 " + EMPTY_BOARD);
        check("a new opponent in the room: a tab with the new name",
              tabTitles().contains("VS Jay") && !tabTitles().contains("VS Ivy"), tabTitles().toString());
        server("OPPONENT_LEFT 13");
        click("Yes, delete it");
        check("delete it: the room is deleted", sent().contains("LEAVE_GAME 13"));
        check("delete it: back in the lobby", !shown(), box());
        check("delete it: the tab is gone", !tabTitles().contains("VS Jay"), tabTitles().toString());
        sent();
        server("GAME_LEFT 13");
        check("GAME_LEFT, the answer to LEAVE_GAME: nothing else happens", !shown() && sent().isEmpty(), box());

        // ---- The tabs: the one the player picks is the game on screen

        server("GAME_START 20 1 Kim");
        server("GAME_STATE 20 1 " + EMPTY_BOARD);
        server("GAME_START 21 2 Lea");
        server("GAME_STATE 21 1 " + EMPTY_BOARD);
        check("two more games, two more tabs", tabTitles().containsAll(List.of("VS Kim", "VS Lea")), tabTitles().toString());
        sent();
        selectTab(21);
        check("tab picked: the server is told", sent().equals(List.of("SET_ACTIVE_GAME 21")));

        // What happens in the game behind is written on the board on screen.
        server("GAME_STATE 20 2 " + EMPTY_BOARD);
        server("GAME_STATE 20 1 " + EMPTY_BOARD);
        check("game behind: the line is on the board on screen", notification().equals("Your turn against Kim"), notification());
        sent();

        server("GAME_STATE 21 0 " + EMPTY_BOARD);
        server("GAME_OVER 21 WIN");
        check("tab picked: its Game Over box", shown() && message().startsWith("You won"), box());
        click("Rematch");
        sent();
        server("ERROR MOVE NOT_ACTIVE");
        check("NOT_ACTIVE: the game on screen is made active again", sent().equals(List.of("SET_ACTIVE_GAME 21")));

        // The tab of a finished game brings its box back, as Resume does.
        click("Home");
        server("MY_GAME_LIST 2 20 r20 Kim 1 PLAYING 1 HERE 21 r21 Lea 2 FINISHED 0 HERE");
        check("My Games: our turn", "Your turn".equals(cell("myGamesTable", 0, 3)), "" + cell("myGamesTable", 0, 3));
        check("My Games: we voted for the rematch", "Waiting for rematch".equals(cell("myGamesTable", 1, 3)), "" + cell("myGamesTable", 1, 3));
        select("myGamesTable", 0);
        lobbyButton("Resume");
        check("Resume: the game has no box", !shown(), box());
        selectTab(21);
        check("finished game's tab: its box again", shown() && message().equals("Waiting for the opponent's decision..."), box());

        // ---- The bell: the requests to join our rooms, during a game

        click("Home");
        select("myGamesTable", 0);
        lobbyButton("Resume");
        check("no request: the bell is empty", bell().isEmpty(), bell());
        server("JOIN_NOTIFY 30 Olga");
        server("JOIN_NOTIFY 31 Pia");
        check("two requests during a game: no box, the bell counts them", !shown() && bell().equals("2"), box() + " / " + bell());
        sent();
        clickBell();
        check("bell: the first request, over the board", shown() && message().equals("Olga wants to join your game. Accept?"), box());
        check("bell: one left", bell().equals("1"), bell());
        click("Accept");
        check("bell: the answer reaches the server", sent().contains("JOIN_RESPONSE 30 1"));
        check("bell: the box closes, the other request keeps waiting", !shown() && bell().equals("1"), box() + " / " + bell());
        server("JOIN_CANCELLED 31");
        check("a request withdrawn: the bell is empty again", bell().isEmpty(), bell());
        server("JOIN_NOTIFY 33 Rita");
        server("GAME_START 32 1 Quinn");
        selectTab(32);
        check("a new board counts the requests too", bell().equals("1"), bell());

        finish("FlowTest");
    }
}
