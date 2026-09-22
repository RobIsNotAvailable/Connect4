import java.util.List;

// Join requests, from both sides. The joiner is told what happened to each
// request, by room name, and the status line follows them. The owner never
// leaves a request waiting with nobody able to answer it: the room takes one
// request at a time, so it would be closed to everyone.
public class JoinTest extends Rig
{
    static final String WAITING_ONE = "Waiting for Carl to accept your request...";

    // Selects a row of Available Games and presses Join.
    static void join(int row) throws Exception
    {
        select("gameTable", row);
        lobbyButton("Join Selected");
    }

    public static void main(String[] args) throws Exception
    {
        start();
        logIn("Anna");
        server("GAME_LIST 3 3 roomA Bob 4 roomB Carl 5 roomZ Zed");

        // ---- The joiner

        join(1);
        check("join: sent", sent().equals(List.of("JOIN_GAME 4")));
        check("join: status", status().equals(WAITING_ONE), status());

        lobbyButton("Join Selected");
        check("double click: not sent twice", sent().isEmpty());
        check("double click: no error", !shown(), box());

        server("JOIN_RESULT 4 0");
        check("declined: status cleared", status().isBlank(), status());
        check("declined: says who and which room", shown() && title().equals("Request declined")
              && message().equals("Carl declined your request to join \"roomB\"."), box());
        click("OK");

        // The room is deleted before the owner answers.
        join(1);
        check("asked again: sent", sent().equals(List.of("JOIN_GAME 4")));
        server("GAME_CLOSED 4");
        check("room closed: status cleared", status().isBlank(), status());
        check("room closed: says so", shown() && title().equals("Room closed")
              && message().equals("\"roomB\" was closed before Carl answered your request."), box());
        click("OK");
        check("room closed: the list is asked again", sent().contains("LIST_GAMES"));

        // A room we did not ask for closes: nothing to say.
        server("GAME_CLOSED 99");
        check("other room closed: no box", !shown(), box());
        check("other room closed: the list is asked again", sent().equals(List.of("LIST_GAMES")));

        // Two requests at once; the second is refused at once.
        server("GAME_LIST 3 3 roomA Bob 4 roomB Carl 5 roomZ Zed");
        join(0);
        join(2);
        List<String> both = sent();
        check("two requests: both sent", both.equals(List.of("JOIN_GAME 3", "JOIN_GAME 5")), both.toString());
        check("two requests: status", status().equals("Waiting for 2 owners to accept your requests..."), status());
        server("ERROR JOIN_GAME NOT_WAITING");
        check("refused: the last one is dropped", status().equals("Waiting for Bob to accept your request..."), status());
        check("refused: says why", shown() && message().equals("That room is no longer waiting for players."), box());
        click("OK");

        // The other one is accepted: the game starts, nothing is waiting.
        server("JOIN_RESULT 3 1");
        check("accepted: no box", !shown(), box());
        server("GAME_START 3 2 Bob");
        server("GAME_STATE 3 1 " + EMPTY_BOARD);
        check("accepted: status cleared", status().isBlank(), status());
        gameButton("Home");
        sent();

        // A request that ends while we play the most games: the server
        // cancelled it (or nobody could have accepted it), and the owner is
        // not the one to blame.
        server("GAME_LIST 1 6 roomC Dora");
        join(0);
        for(int id = 20; id <= 23; id++)
        {
            server("GAME_START " + id + " 1 P" + id);
            server("GAME_STATE " + id + " 1 " + EMPTY_BOARD);
        }
        server("JOIN_RESULT 6 0");
        check("five games: cancelled, not declined", shown() && title().equals("Request cancelled")
              && message().equals("Your request to join \"roomC\" did not go through: you are already playing the maximum number of games (5)."), box());
        click("OK");

        // An answer about a room the front has no record of.
        server("JOIN_RESULT 77 0");
        check("unknown room: still a sentence", shown() && message().startsWith("Your request to join the room"), box());
        click("OK");
        gameButton("Home");
        sent();

        // ---- The owner

        // Accepted while at the most games: the request is declined for the
        // player, instead of waiting forever with its box gone.
        server("JOIN_NOTIFY 8 Dave");
        click("Accept");
        check("accept: sent", sent().equals(List.of("JOIN_RESPONSE 8 1")));
        server("ERROR JOIN_RESPONSE TOO_MANY_MATCHES");
        check("too many games: declined for the player", sent().equals(List.of("JOIN_RESPONSE 8 0")));
        check("too many games: says so", shown() && message().equals("You are already playing the maximum number of games (5), "
              + "so Dave's request was declined. Leave a game to accept new players."), box());
        click("OK");

        // The error names the request answered last, not an older one.
        server("JOIN_NOTIFY 9 Erin");
        click("Decline");
        server("JOIN_NOTIFY 10 Fred");
        click("Accept");
        sent();
        server("ERROR JOIN_RESPONSE TOO_MANY_MATCHES");
        check("the last answered request is declined", sent().equals(List.of("JOIN_RESPONSE 10 0")));
        check("and named", message().contains("Fred's request"), box());
        click("OK");

        finish("JoinTest");
    }
}
