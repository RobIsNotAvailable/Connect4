import com.lso.view.OverlayPanel;

import java.util.List;

// Flows of the lobby and of the boxes: the selection in the tables, a join
// request that arrives while a game starts, and the guard against double
// clicks on a box that changes.
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

        // A room of ours: Delete stays enabled across an update.
        server("GAME_LIST 2 3 roomA Bob 6 mine Anna");
        select("gameTable", 1);
        check("own room: Delete enabled", lobbyButtonEnabled("Delete Room"));
        server("GAME_LIST 2 3 roomA Bob 6 mine Anna");
        check("own room: still selected", selected("gameTable") == 1, "" + selected("gameTable"));
        check("own room: Delete still enabled", lobbyButtonEnabled("Delete Room"));

        // My Games too, and an update of the other table does not take its
        // selection away (the two tables select one row between them).
        server("MY_MATCH_LIST 2 8 g8 Bob 1 PLAYING 1 HERE 9 g9 Carl 2 PLAYING 1 HERE");
        server("MY_GAME_LIST 1 6 mine WAITING");
        select("myGamesTable", 1);
        check("My Games: one row selected in all", selected("myGamesTable") == 1 && selected("gameTable") == -1);
        server("MY_MATCH_LIST 2 8 g8 Bob 1 PLAYING 2 HERE 9 g9 Carl 2 PLAYING 2 HERE");
        check("My Games: still selected", selected("myGamesTable") == 1, "" + selected("myGamesTable"));
        check("My Games: Resume enabled", lobbyButtonEnabled("Resume"));
        server("GAME_LIST 2 3 roomA Bob 6 mine Anna");
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

        // The Game Over box is closed by the rematch that starts.
        server("GAME_OVER 11 WIN");
        click("Rematch");
        server("GAME_START 11 1 Gina");
        server("GAME_STATE 11 1 " + EMPTY_BOARD);
        check("rematch: the box closes", !shown(), box());

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
              "Your opponent left the room. Home keeps it open for another player, Leave room deletes it."), box());
        sent();
        click("Home");
        List<String> home = sent();
        check("Home: the room is kept", !home.contains("LEAVE_GAME 13") && !shown(), home + " / " + box());
        check("Home: back in the lobby", home.contains("SET_ACTIVE_GAME 0") && home.contains("LIST_MY_MATCHES"), home.toString());

        server("GAME_START 13 1 Jay");
        server("GAME_STATE 13 1 " + EMPTY_BOARD);
        server("OPPONENT_LEFT 13");
        click("Leave room");
        check("Leave room: the room is deleted", sent().contains("LEAVE_GAME 13"));
        check("Leave room: back in the lobby", !shown(), box());

        finish("FlowTest");
    }
}
