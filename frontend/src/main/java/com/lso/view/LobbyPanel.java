package com.lso.view;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import com.lso.MainController;
import com.lso.NameCodec;

public class LobbyPanel extends JPanel
{
    private UiUtil.TransparentTable gameTable;
    private JLabel usernameLabel;
    private final String[] COLUMN_NAMES = {"Game ID", "Name", "Owner"};

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

        UiUtil.TransparentScrollPanel scrollPane = new UiUtil.TransparentScrollPanel(gameTable, 600, 400);
        add(scrollPane, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 0));
        buttonPanel.setOpaque(false);

        JButton joinBtn = UiUtil.createStyledButton("Join Selected");
        UiUtil.addListener(joinBtn, e ->
        {
            int row = gameTable.getSelectedRow();
            if(row != -1)
            {
                String gameId = gameTable.getValueAt(row, 0).toString();
                controller.sendMessage("JOIN_GAME " + gameId);
            }
        });

        JButton createBtn = UiUtil.createStyledButton("Create Game");
        UiUtil.addListener(createBtn, e ->
        {
            String name = javax.swing.JOptionPane.showInputDialog(
                this,
                "Room name (up to 20 characters):",
                "Create Game",
                javax.swing.JOptionPane.QUESTION_MESSAGE
            );

            if(name != null && !name.trim().isEmpty())
            {
                controller.sendMessage("CREATE_GAME " + NameCodec.encode(name.trim()));
            }
        });

        buttonPanel.add(joinBtn);
        buttonPanel.add(createBtn);

        add(buttonPanel, BorderLayout.SOUTH);
    }

    public void setUsername(String username)
    {
        usernameLabel.setText(username);
    }

    public void updateGameList(Object[][] data)
    {
        gameTable.setData(data, COLUMN_NAMES);
    }
}