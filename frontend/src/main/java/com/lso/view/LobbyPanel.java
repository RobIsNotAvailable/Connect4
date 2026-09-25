package com.lso.view;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JPanel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import com.lso.controller.GameController;
import com.lso.controller.JoinController;
import com.lso.controller.LobbyController;

public class LobbyPanel extends JPanel
{
    private UiUtil.TransparentTable gameTable;
    private UiUtil.TransparentTable myGamesTable;
    private JLabel usernameLabel;
    private JLabel statusLabel;
    private JButton deleteBtn;
    private JButton resumeBtn;

    // What the buttons call: set by connect(), since these controllers need
    // the panel themselves (to fill the lists and the status line).
    private LobbyController lobby;
    private JoinController joins;
    private GameController games;

    // What the Status column says of a room of ours that has no opponent yet:
    // there is no game to resume, but the room can be deleted.
    public static final String WAITING_STATUS = "Waiting for a player";
    // What it says of a game where it is our move: it stands out (see
    // MyGamesRenderer).
    public static final String YOUR_TURN = "Your turn";


    public LobbyPanel()
    {
        setLayout(new BorderLayout(0, 20));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

        // Three equal columns keep the title exactly centred whatever the
        // width of the username on the right.
        usernameLabel = UiUtil.createStyledLabel("");
        usernameLabel.setIcon(UiUtil.personIcon(28, UiUtil.ACCENT));
        usernameLabel.setIconTextGap(10);
        usernameLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        JPanel header = new JPanel(new GridLayout(1, 3));
        header.setOpaque(false);
        header.add(Box.createHorizontalGlue());
        header.add(UiUtil.createStyledLabel("Open Rooms"));
        header.add(usernameLabel);

        add(header, BorderLayout.NORTH);

        gameTable = new UiUtil.TransparentTable("No rooms to join yet: create one and wait for an opponent",
                                                "Game ID", "Name", "Owner");
        gameTable.onDoubleClick(this::joinSelected);

        // The games we are playing, under the rooms to join. Selecting a row in
        // one table deselects the other, so the buttons below always refer to
        // the one the player is looking at.
        myGamesTable = new UiUtil.TransparentTable("No games yet: join a room above or create one",
                                                   "Game ID", "Name", "Opponent", "Status", "Opponent is");
        myGamesTable.onDoubleClick(this::resumeSelected);
        myGamesTable.setDefaultRenderer(Object.class, new MyGamesRenderer());

        // The columns are kept across updates (see setRows), so the widths
        // are set once: the status needs more room than the names.
        int[] widths = {170, 150, 170, 190};
        for(int i = 0; i < widths.length; i++)
        {
            myGamesTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        JPanel mine = new JPanel(new BorderLayout(0, 10));
        mine.setOpaque(false);
        mine.add(UiUtil.createStyledLabel("My Games"), BorderLayout.NORTH);
        mine.add(new UiUtil.TransparentScrollPanel(myGamesTable, 600, 150), BorderLayout.CENTER);

        JPanel tables = new JPanel(new GridLayout(2, 1, 0, 10));
        tables.setOpaque(false);
        tables.add(new UiUtil.TransparentScrollPanel(gameTable, 600, 150));
        tables.add(mine);
        add(tables, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 0));
        buttonPanel.setOpaque(false);

        JButton joinBtn = UiUtil.createStyledButton("Join Room");
        joinBtn.setEnabled(false);
        joinBtn.addActionListener(e -> joinSelected());

        JButton createBtn = UiUtil.createStyledButton("Create Room");
        createBtn.addActionListener(e -> lobby.askRoomName());

        // Our rooms are not in Open Rooms (the server leaves them out), so
        // the room to delete is the one selected in My Games.
        deleteBtn = UiUtil.createStyledButton("Delete Room");
        deleteBtn.setEnabled(false);
        deleteBtn.addActionListener(e ->
        {
            int row = myGamesTable.getSelectedRow();
            if(row != -1)
            {
                String gameId = myGamesTable.getCellValue(row, 0).toString();
                String roomName = myGamesTable.getCellValue(row, 1).toString();
                lobby.askDeleteRoom(gameId, roomName);
            }
        });
        gameTable.getSelectionModel().addListSelectionListener(e ->
        {
            if(gameTable.getSelectedRow() != -1)
            {
                myGamesTable.clearSelection();
            }
            joinBtn.setEnabled(gameTable.getSelectedRow() != -1);
        });

        resumeBtn = UiUtil.createStyledButton("Resume");
        resumeBtn.setEnabled(false);
        resumeBtn.addActionListener(e -> resumeSelected());
        myGamesTable.getSelectionModel().addListSelectionListener(e ->
        {
            if(myGamesTable.getSelectedRow() != -1)
            {
                gameTable.clearSelection();
            }
            updateResumeButton();
            updateDeleteButton();
        });

        buttonPanel.add(joinBtn);
        buttonPanel.add(createBtn);
        buttonPanel.add(deleteBtn);
        buttonPanel.add(resumeBtn);

        // A blank text keeps the row's height, so the layout does not jump
        // when a message appears.
        statusLabel = UiUtil.createStyledLabel(" ");
        statusLabel.setForeground(UiUtil.ACCENT);
        statusLabel.setFont(statusLabel.getFont().deriveFont(16f));

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(statusLabel, BorderLayout.NORTH);
        bottom.add(buttonPanel, BorderLayout.CENTER);

        add(bottom, BorderLayout.SOUTH);
    }

    public void connect(LobbyController lobby, JoinController joins, GameController games)
    {
        this.lobby = lobby;
        this.joins = joins;
        this.games = games;
    }

    public void setUsername(String username)
    {
        usernameLabel.setText(username);
    }

    // The line above the buttons: the join requests still waiting. The
    // server does not answer one until the owner decides, so without it
    // nothing would show that Join did anything.
    public void setStatus(String text)
    {
        statusLabel.setText(text);
    }

    public void clearStatus()
    {
        statusLabel.setText(" ");
    }

    public void updateGameList(Object[][] rows)
    {
        gameTable.setRows(rows);
    }

    public void updateMyGames(Object[][] rows)
    {
        myGamesTable.setRows(rows);
    }

    private void joinSelected()
    {
        int row = gameTable.getSelectedRow();
        if(row != -1)
        {
            int gameId = Integer.parseInt(gameTable.getCellValue(row, 0).toString());
            String roomName = gameTable.getCellValue(row, 1).toString();
            String owner = gameTable.getCellValue(row, 2).toString();
            joins.join(gameId, roomName, owner);
        }
    }

    private void resumeSelected()
    {
        int row = myGamesTable.getSelectedRow();
        if(row != -1)
        {
            games.resumeGame(Integer.parseInt(myGamesTable.getCellValue(row, 0).toString()));
        }
    }

    // "Your turn" is what a player looks for in this list, so it stands out.
    private static class MyGamesRenderer extends javax.swing.table.DefaultTableCellRenderer
    {
        MyGamesRenderer()
        {
            setHorizontalAlignment(SwingConstants.CENTER); // like the other cells (TransparentTable)
        }

        @Override
        public Component getTableCellRendererComponent(javax.swing.JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int column)
        {
            Component cell = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if(YOUR_TURN.equals(value))
            {
                cell.setFont(cell.getFont().deriveFont(Font.BOLD));
                if(!isSelected)
                {
                    cell.setForeground(UiUtil.ACCENT);
                }
            }
            else
            {
                cell.setFont(cell.getFont().deriveFont(Font.PLAIN));

                // The renderer remembers the colour it is given, so the cells
                // after a "Your turn" would keep it: put the normal one back.
                if(!isSelected)
                {
                    cell.setForeground(table.getForeground());
                }
            }
            return cell;
        }
    }

    // A game can be resumed once it has an opponent: a room that still waits
    // for one has nothing to resume.
    private void updateResumeButton()
    {
        int row = myGamesTable.getSelectedRow();
        resumeBtn.setEnabled(row != -1 && !WAITING_STATUS.equals(myGamesTable.getCellValue(row, 3)));
    }

    // A room can be deleted by its creator while it waits for a player: in My
    // Games that is a waiting row, since a room that waits has only us in it.
    private void updateDeleteButton()
    {
        int row = myGamesTable.getSelectedRow();
        deleteBtn.setEnabled(row != -1 && WAITING_STATUS.equals(myGamesTable.getCellValue(row, 3)));
    }
}