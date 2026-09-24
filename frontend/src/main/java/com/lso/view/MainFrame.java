package com.lso.view;

import java.awt.Dimension;
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

        setLocationRelativeTo(null);
        setVisible(true);
    }
}