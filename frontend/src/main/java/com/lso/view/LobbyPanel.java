package com.lso.view;

import javax.swing.JPanel;
import javax.swing.JButton;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import com.lso.MainController;
import com.lso.NameCodec;

public class LobbyPanel extends JPanel
{
    private UiUtil.TransparentTable gameTable;
    private final String[] COLUMN_NAMES = {"Game ID", "Name", "Owner"};

    public LobbyPanel(MainController controller)
    {
        setLayout(new BorderLayout(0, 20));
        setOpaque(false);

        add(UiUtil.createStyledLabel("Available Games"), BorderLayout.NORTH);

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

    public void updateGameList(Object[][] data)
    {
        gameTable.setData(data, COLUMN_NAMES);
    }
}