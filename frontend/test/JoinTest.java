import java.util.List;

// Join requests, from both sides. The joiner is told what happened to each
// request, by room name, and the status line follows them. The owner's answer
// goes to the server.
public class JoinTest extends Rig
{
    static final String WAITING_ONE = "Waiting for Carl to accept your request...";

    // Selects a row of Open Rooms and presses Join.
    static void join(int row) throws Exception
    {
        select("gameTable", row);
        lobbyButton("Join Room");
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

        lobbyButton("Join Room");
        check("double click: not sent twice", sent().isEmpty());
        check("double click: no error", !shown(), box());

        server("JOIN_RESULT 4 0");
        check("declined: status cleared", status().isBlank(), status());
        check("declined: says who and which room", shown() && title().equals("Request Declined")
              && message().equals("Carl declined your request to join \"roomB\"."), box());
        click("OK");

        // The room is deleted before the owner answers.
        join(1);
        check("asked again: sent", sent().equals(List.of("JOIN_GAME 4")));
        server("GAME_CLOSED 4");
        check("room closed: status cleared", status().isBlank(), status());
        check("room closed: says so", shown() && title().equals("Room Closed")
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

        // A request that ends while we have the most games (the rooms of ours
        // that wait count too, as the last MY_GAME_LIST says): the server
        // cancelled it, and the owner is not the one to blame.
        server("GAME_LIST 1 6 roomC Dora");
        join(0);
        server("MY_GAME_LIST 5 3 r3 Bob 2 PLAYING 1 HERE 20 r20 P20 1 PLAYING 1 HERE 21 r21 P21 1 PLAYING 1 HERE"
               + " 22 r22 - 1 WAITING 0 HERE 23 r23 - 1 WAITING 0 HERE");
        server("JOIN_RESULT 6 0");
        check("five games, waiting rooms included: cancelled, not declined", shown() && title().equals("Request Cancelled")
              && message().equals("Your request to join \"roomC\" did not go through: you already have the maximum number of games (5)."), box());
        click("OK");

        // With a place free, the same answer is the owner's decision.
        server("MY_GAME_LIST 1 3 r3 Bob 2 PLAYING 1 HERE");
        join(0);
        server("JOIN_RESULT 6 0");
        check("a place free: declined", shown() && title().equals("Request Declined"), box());
        click("OK");

        // An answer about a room the front has no record of.
        server("JOIN_RESULT 77 0");
        check("unknown room: still a sentence", shown() && message().equals("Your request to join the room was declined."), box());
        click("OK");
        sent();

        // ---- The owner: both answers go to the server

        server("JOIN_NOTIFY 8 Dave");
        click("Accept");
        check("accept: sent", sent().equals(List.of("JOIN_RESPONSE 8 1")));
        server("JOIN_NOTIFY 9 Erin");
        click("Decline");
        check("decline: sent", sent().equals(List.of("JOIN_RESPONSE 9 0")));

        finish("JoinTest");
    }
}
