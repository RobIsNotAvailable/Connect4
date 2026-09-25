package com.lso.view;

import com.lso.MainController;

// What the player reads when the server refuses something: never the code
// itself, which is already in the "Received:" line on the console.
public final class ErrorText
{
    private ErrorText()
    {
    }

    public static String of(String code)
    {
        switch(code)
        {
            case "NOT_FOUND":       return "That room no longer exists.";
            case "NOT_WAITING":     return "That room is no longer waiting for players.";
            case "SELF_JOIN":       return "You can't join your own room.";
            // A room takes one request at a time: this one may be someone else's.
            case "ALREADY_PENDING": return "Someone is already waiting to join this room. Try again in a moment.";
            case "TOO_MANY_GAMES":  return "You already have the maximum number of games (" + MainController.MAX_MATCHES + "), rooms waiting for a player included. Leave or delete one first.";
            case "SERVER_FULL":     return "The server can't host more games right now.";
            case "INVALID_NAME":    return "That name is not valid: use up to 20 characters (letters, digits and symbols, but no accented letters).";
            case "USERNAME_TAKEN":  return "That username is already taken.";
            case "JOINER_FULL":     return "That player already has the maximum number of games (" + MainController.MAX_MATCHES + "), so their request was cancelled.";
            case "NO_PENDING":      return "That player is no longer waiting to join.";
            default:                return "Something went wrong: the server refused the request.";
        }
    }
}
