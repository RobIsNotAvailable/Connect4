package com.lso.view;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import com.lso.MainController;

public class LobbyPanel extends JPanel
{
    private UiUtil.TransparentTable gameTable;
    private UiUtil.TransparentTable myGamesTable;
    private JLabel usernameLabel;
    private JLabel statusLabel;
    private JButton deleteBtn;
    private JButton resumeBtn;
    private String username;
    // What the Status column says of a room of ours that has no opponent yet:
    // there is no game to resume, but the room can be deleted.
    public static final String WAITING_STATUS = "Waiting for a player";

    private final String[] COLUMN_NAMES = {"Game ID", "Name", "Owner"};
    private final String[] MY_GAMES_COLUMN_NAMES = {"Game ID", "Name", "Opponent", "Status", "Opponent is"};

    public LobbyPanel(MainController controller)
    {
        setLayout(new BorderLayout(0, 20));
        setOpaque(false);

        // Three equal columns keep the title exactly centred whatever the
        // width of the username on the right.
        usernameLabel = UiUtil.createStyledLabel("");
        usernameLabel.setIcon(new UiUtil.PersonIcon(28));
        usernameLabel.setIconTextGap(10);
        usernameLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        usernameLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 20));

        JPanel header = new JPanel(new GridLayout(1, 3));
        header.setOpaque(false);
        header.add(new UiUtil.BlankPanel(new Dimension(0, 0)));
        header.add(UiUtil.createStyledLabel("Available Games"));
        header.add(usernameLabel);

        add(header, BorderLayout.NORTH);

        gameTable = new UiUtil.TransparentTable(new Object[0][3], COLUMN_NAMES);
        gameTable.setEnabled(true);
        gameTable.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        gameTable.hideColumn(0); // the id: the player picks a room by its name

        // The games we are playing, under the rooms to join. Selecting a row in
        // one table deselects the other, so the buttons below always refer to
        // the one the player is looking at.
        myGamesTable = new UiUtil.TransparentTable(new Object[0][5], MY_GAMES_COLUMN_NAMES);
        myGamesTable.setEnabled(true);
        myGamesTable.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        myGamesTable.setDefaultRenderer(Object.class, new MyGamesRenderer());
        myGamesTable.hideColumn(0);

        // The columns are kept across updates (see hideColumn), so the widths
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

        JButton joinBtn = UiUtil.createStyledButton("Join Selected");
        UiUtil.addListener(joinBtn, e ->
        {
            int row = gameTable.getSelectedRow();
            if(row != -1)
            {
                int gameId = Integer.parseInt(gameTable.getCellValue(row, 0).toString());
                String roomName = gameTable.getCellValue(row, 1).toString();
                String owner = gameTable.getCellValue(row, 2).toString();
                controller.joinGame(gameId, roomName, owner);
            }
        });

        JButton createBtn = UiUtil.createStyledButton("Create Game");
        UiUtil.addListener(createBtn, e -> controller.askRoomName());

        deleteBtn = UiUtil.createStyledButton("Delete Room");
        deleteBtn.setEnabled(false);
        UiUtil.addListener(deleteBtn, e ->
        {
            // The room is in Available Games and, since it is ours, in My Games
            // too: the button acts on the table that has a row selected.
            UiUtil.TransparentTable table = (myGamesTable.getSelectedRow() != -1) ? myGamesTable : gameTable;
            int row = table.getSelectedRow();
            if(row != -1)
            {
                String gameId = table.getCellValue(row, 0).toString();
                String roomName = table.getCellValue(row, 1).toString();
                controller.askDeleteRoom(gameId, roomName);
            }
        });
        gameTable.getSelectionModel().addListSelectionListener(e ->
        {
            if(gameTable.getSelectedRow() != -1)
            {
                myGamesTable.clearSelection();
            }
            updateDeleteButton();
        });

        resumeBtn = UiUtil.createStyledButton("Resume");
        resumeBtn.setEnabled(false);
        UiUtil.addListener(resumeBtn, e -> resumeSelected(controller));
        myGamesTable.getSelectionModel().addListSelectionListener(e ->
        {
            if(myGamesTable.getSelectedRow() != -1)
            {
                gameTable.clearSelection();
            }
            updateResumeButton();
            updateDeleteButton();
        });
        myGamesTable.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                if(e.getClickCount() == 2 && myGamesTable.rowAtPoint(e.getPoint()) != -1)
                {
                    resumeSelected(controller);
                }
            }
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

    public void setUsername(String username)
    {
        this.username = username;
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

    // The rooms are listed again whenever anyone creates, joins or leaves one,
    // so the selected room stays selected across the update.
    public void updateGameList(Object[][] data)
    {
        setDataKeepingSelection(gameTable, data, COLUMN_NAMES);
        updateDeleteButton();
    }

    // The list of the games we are playing is asked again after every move of
    // the opponents, so the selected game stays selected across the update.
    public void updateMyGames(Object[][] rows)
    {
        setDataKeepingSelection(myGamesTable, rows, MY_GAMES_COLUMN_NAMES);
    }

    // New data means a new model, which clears the selection: the row with
    // the same id (column 0) is selected again, if it is still there.
    private static void setDataKeepingSelection(UiUtil.TransparentTable table, Object[][] rows, String[] columnNames)
    {
        int row = table.getSelectedRow();
        Object selectedId = (row != -1) ? table.getCellValue(row, 0) : null;

        table.setData(rows, columnNames);

        for(int i = 0; i < rows.length; i++)
        {
            if(rows[i][0].equals(selectedId))
            {
                table.setRowSelectionInterval(i, i);
                break;
            }
        }
    }

    private void resumeSelected(MainController controller)
    {
        int row = myGamesTable.getSelectedRow();
        if(row != -1)
        {
            controller.resumeGame(Integer.parseInt(myGamesTable.getCellValue(row, 0).toString()));
        }
    }

    // "Your turn" is what a player looks for in this list, so it stands out.
    private static class MyGamesRenderer extends javax.swing.table.DefaultTableCellRenderer
    {
        @Override
        public Component getTableCellRendererComponent(javax.swing.JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int column)
        {
            Component cell = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if("Your turn".equals(value))
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

    // Only the creator can delete a room, so the button is enabled when the
    // selected row is a room of ours: in Available Games it is one whose owner
    // is us, in My Games one that is still waiting.
    private void updateDeleteButton()
    {
        int row = gameTable.getSelectedRow();
        int myRow = myGamesTable.getSelectedRow();
        boolean ownRoom = row != -1 && gameTable.getCellValue(row, 2).equals(username);
        boolean waitingRoom = myRow != -1 && WAITING_STATUS.equals(myGamesTable.getCellValue(myRow, 3));

        deleteBtn.setEnabled(ownRoom || waitingRoom);
    }
}