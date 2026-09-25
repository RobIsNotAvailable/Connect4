package com.lso;

// Everything the client knows about one game it plays: who the players are,
// the board, whose turn it is and how it ended. The controller keeps one per
// game in progress. A player can be in several at once (docs/protocol.md
// §5.1), and each message of the server reaches the game it names, whichever
// one is on screen.
public class GameSession
{
    // The board of docs/protocol.md §5.2: ROWS * COLUMNS characters, top row
    // first, '.' for an empty cell and '1' / '2' for the discs.
    public static final int ROWS = 6;
    public static final int COLUMNS = 7;
    public static final String EMPTY_BOARD = ".".repeat(ROWS * COLUMNS);

    // How a game ended for us (GAME_OVER), with the words the player reads.
    public enum Result
    {
        WIN("You won"),
        LOSE("You lost"),
        DRAW("It's a draw");

        public final String text;

        Result(String text)
        {
            this.text = text;
        }
    }

    private final int id;
    private final int myPlayer; // 1 or 2
    private final String myName;
    private final String opponent;

    private String board = EMPTY_BOARD;
    private int lastMove = -1;  // where in 'board' the disc of the last move is, -1 if none
    private int turn = 1;       // 1 or 2, and 0 once the game is over
    private Result result;      // null until the game is over
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

    public Result getResult()
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

    // The last move is the one cell that went from empty to a disc. A board
    // that did not change keeps it; any other change has none.
    public void update(int turn, String board)
    {
        int changed = -1;
        for(int i = 0; i < board.length(); i++)
        {
            if(board.charAt(i) != this.board.charAt(i))
            {
                changed = (changed == -1) ? i : -2; // -2: more than one
            }
        }
        if(changed != -1)
        {
            lastMove = (changed >= 0 && this.board.charAt(changed) == '.') ? changed : -1;
        }
        this.turn = turn;
        this.board = board;
    }

    public void setResult(Result result)
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

    // '.', '1' or '2'. Row 0 is the top one.
    public char cell(int row, int col)
    {
        return board.charAt(row * COLUMNS + col);
    }

    public boolean isLastMove(int row, int col)
    {
        return row * COLUMNS + col == lastMove;
    }

    // Only on our turn and in a column that still has room: the server would
    // refuse anything else with an error. A column is full when its top cell
    // is taken.
    public boolean canPlay(int col)
    {
        return turn == myPlayer && cell(0, col) == '.';
    }

    // The row where a disc dropped in this column stops: the lowest empty
    // one, or -1 if the column is full.
    public int landingRow(int col)
    {
        for(int row = ROWS - 1; row >= 0; row--)
        {
            if(cell(row, col) == '.')
            {
                return row;
            }
        }
        return -1;
    }

    // What the list of our games says of this game once it is over: the
    // rematch if someone voted for it, otherwise how it ended.
    public String finishedText()
    {
        if(iWantRematch)
        {
            return "Waiting for rematch";
        }
        if(opponentWantsRematch)
        {
            return "Rematch requested";
        }
        return (result != null) ? result.text : "Finished";
    }
}
