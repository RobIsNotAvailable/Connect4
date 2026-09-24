package com.lso.view;


import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import com.formdev.flatlaf.FlatDarkLaf;

public class UiUtil
{
    public static final Color BACKGROUND_BLACK = new Color(35, 35, 35);

    public static final Color BACKGROUND_GRAY = new Color(84, 84, 84, 255);

    public static final Color BACKGROUND_BAR = new Color(46, 46, 46);

    public static final Color ACCENT = new Color(200,165,140);

    public static final Color ACCENT_SECONDARY = new Color(107, 88, 75);

    public static final Color ERROR_RED = new Color (224, 58, 58);

    public static final Color SUCCESS_GREEN = new Color (58, 224, 97);

    // FlatLaf, dark, with the colours above for what it draws itself: the
    // headers of the tables and the scroll bars.
    public static void installLookAndFeel()
    {
        FlatDarkLaf.setup();
        JFrame.setDefaultLookAndFeelDecorated(true);

        UIManager.put("TableHeader.background", ACCENT_SECONDARY);
        UIManager.put("TableHeader.foreground", Color.WHITE);
        UIManager.put("TableHeader.separatorColor", ACCENT_SECONDARY);
        UIManager.put("TableHeader.bottomSeparatorColor", ACCENT);
        UIManager.put("ScrollBar.thumb", ACCENT);
        UIManager.put("ScrollBar.hoverThumbColor", ACCENT);
        UIManager.put("ScrollBar.track", BACKGROUND_BLACK);
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new Insets(0, 2, 0, 2));
    }

    // A person, in the colour of a player or of the accent.
    public static Icon personIcon(int size, Color color)
    {
        return FontIcon.of(FontAwesomeSolid.USER, size, color);
    }

    // The colour of the discs of player 1 or 2.
    public static Color playerColor(int player)
    {
        return (player == 1) ? ERROR_RED : SUCCESS_GREEN;
    }

    public static Color withAlpha(Color color, int alpha)
    {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    public static JButton createStyledButton(String text)
    {
        JButton button = new JButton(text);
        button.setFont(new Font("Arial", Font.BOLD, 18));
        button.setForeground(Color.WHITE);

        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setFocusable(false);
        button.setMargin(new Insets(10, 15, 15, 15));

        button.setCursor(new Cursor(Cursor.HAND_CURSOR));

        return button;
    }

    public static JLabel createStyledLabel(String text)
    {
        JLabel label = new JLabel(text, SwingConstants.CENTER);

        label.setForeground(Color.WHITE);
        label.setFont(new Font("Arial", Font.BOLD, 20));

        return label;
    }

    // A line of text on a coloured strip (see setStrip). It is always there,
    // blank and see-through when there is nothing to say, so the layout
    // around it does not change when a message appears.
    public static JLabel createStripLabel(Color background, Color foreground)
    {
        JLabel label = createStyledLabel(" ");
        label.setFont(label.getFont().deriveFont(16f));
        label.setForeground(foreground);
        label.setBackground(background);
        label.setBorder(BorderFactory.createEmptyBorder(5, 20, 5, 20));
        return label;
    }

    // Shows 'text' on a strip, or blanks it when 'text' is null.
    public static void setStrip(JLabel strip, String text)
    {
        strip.setText(text != null ? text : " ");
        strip.setOpaque(text != null);
        strip.repaint();
    }


    // A list of rooms or games: one row can be selected, and the first column
    // holds the id, which the code reads but the player does not see.
    public static class TransparentTable extends JTable
    {
        private final String[] columnNames;

        public TransparentTable(String... columnNames)
        {
            super(new Object[0][columnNames.length], columnNames);
            this.columnNames = columnNames;

            setFillsViewportHeight(true);
            setOpaque(false);
            setBackground(new Color(0, 0, 0, 0));
            setForeground(Color.WHITE);
            setFont(getFont().deriveFont(20f));
            setRowHeight(30);
            setShowGrid(false);
            setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);

            // The id stays in the model, so its values can be read, but it is
            // not shown. The columns are kept when the data is replaced
            // (setRows), otherwise a new model would bring it back.
            setAutoCreateColumnsFromModel(false);
            removeColumn(getColumnModel().getColumn(0));

            // The header is drawn by FlatLaf, in the colours of installLookAndFeel.
            // The columns stay where they are, as wide as they are.
            getTableHeader().setFont(getFont().deriveFont(Font.BOLD, 16f));
            getTableHeader().setReorderingAllowed(false);
            getTableHeader().setResizingAllowed(false);
        }

        // The rows only display what the server sent: a double click must
        // not turn a cell into a text field.
        @Override
        public boolean isCellEditable(int row, int column)
        {
            return false;
        }

        // New rows mean a new model, which clears the selection: the row with
        // the same id is selected again, if it is still there. The lists are
        // sent again whenever anything changes, and the player must not lose
        // the row they picked.
        public void setRows(Object[][] rows)
        {
            int row = getSelectedRow();
            Object selectedId = (row != -1) ? getCellValue(row, 0) : null;

            setModel(new javax.swing.table.DefaultTableModel(rows, columnNames));

            for(int i = 0; i < rows.length; i++)
            {
                if(rows[i][0].equals(selectedId))
                {
                    setRowSelectionInterval(i, i);
                    break;
                }
            }
        }

        // Reads the model, not the view: unlike getValueAt it does not depend
        // on which columns are hidden.
        public Object getCellValue(int row, int modelColumn)
        {
            return getModel().getValueAt(row, modelColumn);
        }
    }

    public static class TransparentScrollPanel extends JScrollPane
    {
        public TransparentScrollPanel(Component component, int width, int height)
        {
            super(component);

            setPreferredSize(new Dimension(width, height));
            setOpaque(false);
            getViewport().setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder());

            setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

            // The scroll bar is drawn by FlatLaf (see installLookAndFeel).
            getVerticalScrollBar().setOpaque(false);
            getVerticalScrollBar().setUnitIncrement(20);
        }
    }

    public static void addKeyBinding(JButton button, String keyName)
    {
        InputMap inputMap = button.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = button.getActionMap();

        inputMap.put(KeyStroke.getKeyStroke(keyName), "click");

        actionMap.put("click", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                button.doClick();
            }
        });
    }
}