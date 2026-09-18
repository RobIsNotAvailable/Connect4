package com.lso.view;

import java.awt.GraphicsEnvironment;
import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.Rectangle;

import javax.swing.JFrame;

public class MainFrame extends JFrame 
{
    public MainFrame()
    {
        setTitle("Connect 4");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); 
        setSize(1024, 768);
        setMinimumSize(new Dimension(800, 600));
        getContentPane().setBackground(UiUtil.BACKGROUND_BLACK);

        Rectangle screen = getScreenSize();
        int x = ((int) screen.getMaxX() - getWidth()) / 2;
        int y = ((int) screen.getMaxY() - getHeight()) / 2;
        setLocation(x, y);
        setVisible(true);
    }

    private Rectangle getScreenSize()
    {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice defaultScreen = ge.getDefaultScreenDevice();
        return defaultScreen.getDefaultConfiguration().getBounds();
    }
}