package com.lso;

import java.awt.CardLayout;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import com.lso.view.MainFrame;
import com.lso.view.GamePanel;
import com.lso.view.LobbyPanel;

public class MainController 
{
    private MainFrame mainFrame;
    private JPanel mainPanel;
    private CardLayout cardLayout;
    private PrintWriter out;
    
    private LobbyPanel lobbyPanel;
    private GamePanel gamePanel;

    public MainController() 
    {
        mainFrame = new MainFrame();
        cardLayout = new CardLayout();
        mainPanel = new JPanel(cardLayout);
        mainPanel.setOpaque(false);

        lobbyPanel = new LobbyPanel(this);
        mainPanel.add(lobbyPanel, "Lobby"); 

        gamePanel = new GamePanel(this);
        mainPanel.add(gamePanel, "Game");
        
        mainFrame.add(mainPanel);

        startNetwork();
    }

    public void showScreen(String screenName) 
    {
        cardLayout.show(mainPanel, screenName);
    }

    private void startNetwork()
    {
        new Thread(() ->
        {
            try (Socket socket = new Socket("127.0.0.1", 8080);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream())))
            {
                out = new PrintWriter(socket.getOutputStream(), true);

                String line;
                while((line = in.readLine()) != null)
                {
                    String msg = line;
                    SwingUtilities.invokeLater(() ->
                    {
                        handleServerMessage(msg);
                    });
                }
            }
            catch(Exception e)
            {
                System.err.println("Connection error: " + e.getMessage());
            }
        }).start();
    }

    private void handleServerMessage(String msg)
    {
        System.out.println("Received: " + msg);
        String[] parts = msg.split(" ");
        String cmd = parts[0];

        switch(cmd)
        {
            case "WELCOME":
                askUsername("Choose a username:");
                break;

            case "USERNAME_SET":
                sendMessage("LIST_GAMES");
                break;

            case "GAME_LIST":
                int count = Integer.parseInt(parts[1]);
                Object[][] data = new Object[count][3];
                int index = 2;

                for(int i = 0; i < count; i++)
                {
                    data[i][0] = parts[index++];
                    data[i][1] = parts[index++];
                    data[i][2] = parts[index++];
                }
                lobbyPanel.updateGameList(data);
                break;

            case "GAME_CREATED":
            case "NEW_GAME":
            case "GAME_CLOSED":
                sendMessage("LIST_GAMES");
                break;

            case "JOIN_NOTIFY":
                String gameId = parts[1];
                String joiner = parts[2];

                int choice = javax.swing.JOptionPane.showConfirmDialog(
                    mainFrame,
                    joiner + " wants to join your game. Accept?",
                    "Join Request",
                    javax.swing.JOptionPane.YES_NO_OPTION
                );

                int accepted = (choice == javax.swing.JOptionPane.YES_OPTION) ? 1 : 0;
                sendMessage("JOIN_RESPONSE " + gameId + " " + accepted);
                break;

            case "JOIN_RESULT":
                if(parts[2].equals("0"))
                {
                    javax.swing.JOptionPane.showMessageDialog(
                        mainFrame,
                        "Your request was rejected by the owner.",
                        "Access Denied",
                        javax.swing.JOptionPane.WARNING_MESSAGE
                    );
                }
                break;

            case "GAME_START":
                int id = Integer.parseInt(parts[1]);
                int myPlayer = Integer.parseInt(parts[2]);
                String opponent = parts[3];
                
                gamePanel.setupGame(id, myPlayer, opponent);
                showScreen("Game");
                break;

            case "GAME_STATE":
                int turn = Integer.parseInt(parts[2]);
                String boardStr = parts[3];
                gamePanel.updateState(turn, boardStr);
                break;

            case "GAME_OVER":
                askRematch(parts[1], parts[2]);
                break;

            case "ERROR":
                if(parts[1].equals("SET_USERNAME"))
                {
                    askUsername("Username not accepted (" + parts[2] + "). Choose another one:");
                    break;
                }

                javax.swing.JOptionPane.showMessageDialog(
                    mainFrame,
                    "Server error on command " + parts[1] + ": " + parts[2],
                    "Error",
                    javax.swing.JOptionPane.ERROR_MESSAGE
                );
                break;

            default:
                System.out.println("Unhandled command: " + cmd);
                break;
        }
    }

    // The server refuses every command until a username is set, so this is
    // the first thing the client does. Closing the dialog quits the app.
    private void askUsername(String prompt)
    {
        String name = javax.swing.JOptionPane.showInputDialog(
            mainFrame,
            prompt,
            "Username",
            javax.swing.JOptionPane.QUESTION_MESSAGE
        );

        if(name == null)
        {
            System.exit(0);
        }

        sendMessage("SET_USERNAME " + name.trim());
    }

    // The room survives the end of the game, so the player has to choose:
    // vote for a rematch (it starts only if the opponent votes too) or leave
    // the room. Closing the dialog counts as leaving.
    private void askRematch(String gameId, String result)
    {
        String message;
        if(result.equals("WIN"))
        {
            message = "You won!";
        }
        else if(result.equals("LOSE"))
        {
            message = "You lost.";
        }
        else
        {
            message = "It's a draw.";
        }

        Object[] options = {"Rematch", "Leave room"};
        int choice = javax.swing.JOptionPane.showOptionDialog(
            mainFrame,
            message,
            "Game Over",
            javax.swing.JOptionPane.DEFAULT_OPTION,
            javax.swing.JOptionPane.INFORMATION_MESSAGE,
            null,
            options,
            options[0]
        );

        if(choice == 0)
        {
            sendMessage("REMATCH " + gameId);
            gamePanel.showWaitingForRematch();
        }
        else
        {
            sendMessage("LEAVE_GAME " + gameId);
            showScreen("Lobby");
            sendMessage("LIST_GAMES");
        }
    }

    public void sendMessage(String msg)
    {
        if(out != null)
        {
            out.println(msg);
        }
    }

    static void setLaF()
    {
        try
        {
            javax.swing.UIManager.setLookAndFeel("com.formdev.flatlaf.FlatDarkLaf");
            JFrame.setDefaultLookAndFeelDecorated(true);
        }
        catch(Exception e)
        {
            System.err.println("Failed to set Look and Feel: " + e.getMessage());
        }
    }

    public static void main(String[] args) 
    { 
        SwingUtilities.invokeLater(() ->
        {
            setLaF();
            new MainController();
        });
    }
}