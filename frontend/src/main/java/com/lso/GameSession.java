package com.lso;

// Everything the client knows about one game it plays: who the players are,
// the board, whose turn it is and how it ended. The controller keeps one per
// game in progress. A player can be in several at once (docs/protocol.md
// §5.1), and each message of the server reaches the game it names, whichever
// one is on screen.
public class GameSession
{
    public static final String EMPTY_BOARD = ".".repeat(42);

    private final int id;
    private final int myPlayer; // 1 or 2
    private final String myName;
    private final String opponent;

    private String board = EMPTY_BOARD;
    private int turn = 1;       // 1 or 2, and 0 once the game is over
    private String result;      // WIN, LOSE or DRAW once it is over, null before
    private boolean opponentAway; // the opponent is not in this game: in the lobby or in another (OPPONENT_STATUS)
    private boolean iWantRematch;
    private boolean opponentWantsRematch;

    public GameSession(int id, int myPlayer, String myName, String opponent)
    {
        this.id = id;
        this.myPlayer = myPlayer;
        this.myName = myName;
        this.opponent = opponent;
    }

    public int getId()
    {
        return id;
    }

    public int getMyPlayer()
    {
        return myPlayer;
    }

    public String getOpponent()
    {
        return opponent;
    }

    // The name of player 1 or 2.
    public String getPlayerName(int player)
    {
        return player == myPlayer ? myName : opponent;
    }

    public String getBoard()
    {
        return board;
    }

    public int getTurn()
    {
        return turn;
    }

    public String getResult()
    {
        return result;
    }

    public boolean isOpponentAway()
    {
        return opponentAway;
    }

    public boolean iWantRematch()
    {
        return iWantRematch;
    }

    public boolean opponentWantsRematch()
    {
        return opponentWantsRematch;
    }

    public void update(int turn, String board)
    {
        this.turn = turn;
        this.board = board;
    }

    public void setResult(String result)
    {
        this.result = result;
    }

    public void setOpponentAway(boolean opponentAway)
    {
        this.opponentAway = opponentAway;
    }

    public void setIWantRematch(boolean iWantRematch)
    {
        this.iWantRematch = iWantRematch;
    }

    public void setOpponentWantsRematch(boolean opponentWantsRematch)
    {
        this.opponentWantsRematch = opponentWantsRematch;
    }

    // Only on our turn and in a column that still has room: the server would
    // refuse anything else with an error. The top row is row 0, so a column
    // is full when its first cell is taken.
    public boolean canPlay(int col)
    {
        return turn == myPlayer && board.charAt(col) == '.';
    }

    // The row where a disc dropped in this column stops: the lowest empty
    // one (row 5 is the bottom), or -1 if the column is full.
    public int landingRow(int col)
    {
        for(int row = 5; row >= 0; row--)
        {
            if(board.charAt(row * 7 + col) == '.')
            {
                return row;
            }
        }
        return -1;
    }
}
