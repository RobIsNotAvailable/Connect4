package com.lso.view;

import java.awt.Color;

import javax.swing.JTabbedPane;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import com.lso.GameSession;
import com.lso.controller.GameController;
import com.lso.controller.GameEndController;

// The game screen: one tab per game, each a GamePanel showing its session.
public class GameTabs extends JTabbedPane
{
    private final GameController games;
    private final GameEndController ends;
    private int waitingRequests; // shown by the bell of every board

    public GameTabs(GameController games, GameEndController ends)
    {
        this.games = games;
        this.ends = ends;
    }

    // Shows 'session' in the tab of its game, or in a new tab. A rematch, or a
    // new opponent in a room of ours, keeps the tab of the game.
    public void showGame(GameSession session)
    {
        String title = "VS " + session.getOpponent();
        GamePanel panel = panelOf(session.getId());
        if(panel == null)
        {
            panel = new GamePanel(games, ends, session);
            panel.setWaitingRequests(waitingRequests);
            addTab(title, panel);
        }
        else
        {
            panel.show(session);
            setTitleAt(indexOfComponent(panel), title);
        }
    }

    // How many requests to join our rooms wait: every board shows it.
    public void setWaitingRequests(int count)
    {
        waitingRequests = count;
        for(int i = 0; i < getTabCount(); i++)
        {
            ((GamePanel) getComponentAt(i)).setWaitingRequests(count);
        }
    }

    // The tab of game 'id', or null.
    public GamePanel panelOf(int id)
    {
        for(int i = 0; i < getTabCount(); i++)
        {
            GamePanel panel = (GamePanel) getComponentAt(i);
            if(panel.getSession().getId() == id)
            {
                return panel;
            }
        }
        return null;
    }

    // The game of the selected tab, or null.
    public GameSession selectedGame()
    {
        GamePanel panel = (GamePanel) getSelectedComponent();
        return (panel != null) ? panel.getSession() : null;
    }

    public void select(int id)
    {
        setSelectedComponent(panelOf(id));
    }

    public void removeGame(int id)
    {
        GamePanel panel = panelOf(id);
        if(panel != null)
        {
            remove(panel);
        }
    }

    // A green dot on the tab when it is our turn in that game, grey otherwise.
    public void setMyTurn(int id, boolean myTurn)
    {
        Color color = myTurn ? UiUtil.TURN_GREEN : UiUtil.BACKGROUND_GRAY;
        setIconAt(indexOfComponent(panelOf(id)), FontIcon.of(FontAwesomeSolid.CIRCLE, 12, color));
    }
}
