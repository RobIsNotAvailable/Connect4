import java.util.List;

// What the player reads when the server refuses something: a sentence and
// never a code, or nothing at all when the refusal comes from a race that
// another message already explains.
public class ErrorTest extends Rig
{
    public static void main(String[] args) throws Exception
    {
        start();

        // ---- The username box

        server("WELCOME");
        check("asks for a username", shown() && message().equals("Choose a username:"), box());

        for(String blank : new String[] {"", "   "})
        {
            type(blank, false);
            check("\"" + blank + "\": nothing sent", sent().isEmpty());
            check("\"" + blank + "\": says so", shown() && message().equals("Please type a username:"), box());
        }
        type("", true);
        check("empty with Enter: nothing sent", sent().isEmpty());
        check("empty with Enter: says so", shown() && message().equals("Please type a username:"), box());

        type("Anna", false);
        check("the name is sent", sent().equals(List.of("SET_USERNAME Anna")));
        server("ERROR SET_USERNAME USERNAME_TAKEN");
        check("taken", shown() && message().equals("That username is already taken. Choose another one:"), box());
        check("taken: the name is still there to correct", typed().equals("Anna"), typed());
        server("ERROR SET_USERNAME INVALID_NAME");
        check("not valid", shown() && message().startsWith("That name is not valid")
              && message().endsWith("Choose another one:"), box());
        check("not valid: the name is still there", typed().equals("Anna"), typed());
        type("", false);
        check("emptied on purpose: stays empty", typed().isEmpty() && message().equals("Please type a username:"), typed());

        // OK and then Enter before the answer: the name goes twice, and the
        // refusal of the second must not ask for a name again.
        type("Anna", false);
        type("Anna", true);
        check("the name is sent twice", sent().equals(List.of("SET_USERNAME Anna", "SET_USERNAME Anna")));
        server("USERNAME_SET Anna");
        check("accepted: the box closes", !shown(), box());
        server("ERROR SET_USERNAME ALREADY_NAMED");
        check("second refusal: no box", !shown(), box());
        sent();

        // ---- Refusals that only come from races: nothing on screen

        String[][] silent = {
            {"MOVE", "NOT_YOUR_TURN"}, {"MOVE", "COLUMN_FULL"}, {"MOVE", "NOT_PLAYING"}, {"MOVE", "NOT_PLAYER"},
            {"MOVE", "NOT_FOUND"}, {"MOVE", "INVALID_COLUMN"}, {"MOVE", "BAD_ARGS"}, {"MOVE", "NOT_ACTIVE"},
            {"REMATCH", "NOT_FINISHED"}, {"REMATCH", "ALREADY_PENDING"}, {"REMATCH", "NOT_FOUND"}, {"REMATCH", "NOT_PLAYER"},
            {"LEAVE_GAME", "NOT_FOUND"}, {"LEAVE_GAME", "NOT_PLAYER"},
            {"SET_ACTIVE_GAME", "NOT_FOUND"}, {"SET_ACTIVE_GAME", "NOT_PLAYING"},
        };
        for(String[] error : silent)
        {
            server("ERROR " + error[0] + " " + error[1]);
            check("silent: " + error[0] + " " + error[1], !shown(), box());
        }
        check("NOT_ACTIVE with no game on screen: nothing sent", sent().isEmpty());

        // ---- Refusals the player must read: a sentence, no code

        String unexpected = "Something went wrong: the server refused the request.";
        String[][] shownErrors = {
            {"JOIN_GAME", "NOT_FOUND", "That room no longer exists."},
            {"JOIN_GAME", "NOT_WAITING", "That room is no longer waiting for players."},
            {"JOIN_GAME", "SELF_JOIN", "You can't join your own room."},
            {"JOIN_GAME", "ALREADY_PENDING", "Someone is already waiting to join this room. Try again in a moment."},
            {"JOIN_GAME", "TOO_MANY_GAMES", "You already have the maximum number of games (5), rooms waiting for a player included. Leave or delete one first."},
            {"JOIN_GAME", "BAD_ARGS", unexpected},
            {"CREATE_GAME", "SERVER_FULL", "The server can't host more games right now."},
            {"CREATE_GAME", "TOO_MANY_GAMES", "You already have the maximum number of games (5), rooms waiting for a player included. Leave or delete one first."},
            {"CREATE_GAME", "BAD_ARGS", unexpected},
            {"JOIN_RESPONSE", "NOT_FOUND", "That room no longer exists."},
            {"JOIN_RESPONSE", "NOT_OWNER", unexpected},
            {"JOIN_RESPONSE", "NO_PENDING", "That player is no longer waiting to join."},
            {"JOIN_RESPONSE", "JOINER_FULL", "That player already has the maximum number of games (5), so their request was cancelled."},
            {"-", "UNKNOWN_COMMAND", unexpected},
            {"LIST_GAMES", "NO_USERNAME", unexpected},
        };
        for(String[] error : shownErrors)
        {
            String name = error[0] + " " + error[1];
            server("ERROR " + name);
            check("box: " + name, shown() && title().equals("Error") && message().equals(error[2]), box());
            check("no code on screen: " + name, !message().contains(error[1]));
            click("OK");
            check("OK closes it: " + name, !shown(), box());
        }
        sent();

        // ---- The Create Game box

        lobbyButton("Create Game");
        check("create: asks for a name", shown() && title().equals("Create Game")
              && message().equals("Room name:") && typed().isEmpty(), box());
        for(String blank : new String[] {"", "   "})
        {
            type(blank, false);
            check("create \"" + blank + "\": nothing sent", sent().isEmpty());
            check("create \"" + blank + "\": says so", shown() && message().equals("Please type a name for the room:"), box());
        }
        type("my room", false);
        check("create: the name is sent", sent().equals(List.of("CREATE_GAME my|room")));
        check("create: the box closes", !shown(), box());
        server("ERROR CREATE_GAME INVALID_NAME");
        check("create refused: the box is back", shown() && title().equals("Create Game")
              && message().equals("That name is not valid: use up to 20 letters without accents, digits or symbols. Choose another one:"), box());
        check("create refused: with the name to correct", typed().equals("my room"), typed());
        click("Cancel");
        check("create: Cancel closes it", !shown(), box());
        lobbyButton("Create Game");
        check("create again: starts empty", typed().isEmpty(), typed());
        click("Cancel");

        // A join request that arrives while the box is open waits for it, and
        // must not be lost when the box comes back after a refusal.
        lobbyButton("Create Game");
        server("JOIN_NOTIFY 6 Ivy");
        type("room", false);
        check("create + request: the request shows", shown() && message().equals("Ivy wants to join your game. Accept?"), box());
        sent();
        server("ERROR CREATE_GAME INVALID_NAME");
        check("create + request: the name box takes its place", shown() && title().equals("Create Game"), box());
        click("Cancel");
        check("create + request: the request is back", shown() && message().equals("Ivy wants to join your game. Accept?"), box());
        click("Decline");
        check("create + request: answered", sent().equals(List.of("JOIN_RESPONSE 6 0")));

        // ---- The real races: the opponent leaves as we move, or as we vote
        // for a rematch. Only their box, and no error after it.

        server("GAME_START 7 1 Bob");
        server("GAME_STATE 7 1 " + EMPTY_BOARD);
        server("OPPONENT_LEFT 7");
        server("ERROR MOVE NOT_PLAYING");
        check("move: the opponent-left box", shown() && message().startsWith("Your opponent left the room"), box());
        click("Yes, delete it");
        check("move: nothing after it", !shown(), box());

        server("GAME_START 8 2 Bob");
        server("GAME_STATE 8 1 " + EMPTY_BOARD);
        server("GAME_OVER 8 WIN");
        click("Rematch");
        check("rematch: the vote is sent", sent().contains("REMATCH 8"));
        server("OPPONENT_LEFT 8");
        server("ERROR REMATCH NOT_FINISHED");
        check("rematch: the opponent-left box", shown() && message().startsWith("Your opponent left the room"), box());
        click("Yes, delete it");
        server("ERROR LEAVE_GAME NOT_FOUND");
        check("rematch: nothing after it", !shown(), box());

        finish("ErrorTest");
    }
}
