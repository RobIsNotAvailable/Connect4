package com.lso.view;


import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Map;

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
import javax.swing.table.DefaultTableCellRenderer;

import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.swing.FontIcon;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;

public class UiUtil
{
    public static final Color BACKGROUND_BLACK = new Color(35, 35, 35);

    public static final Color BACKGROUND_GRAY = new Color(84, 84, 84, 255);

    public static final Color BACKGROUND_BAR = new Color(46, 46, 46);

    public static final Color ACCENT = new Color(200,165,140);

    public static final Color ACCENT_SECONDARY = new Color(107, 88, 75);

    // The discs, red and yellow as in the real game, and the empty holes:
    // darker than the background, so both colours stand out.
    public static final Color PLAYER1_RED = new Color(224, 58, 58);

    public static final Color PLAYER2_YELLOW = new Color(242, 194, 48);

    public static final Color BOARD_HOLE = new Color(20, 20, 20);

    // The dot on the tab of a game where it is our turn: a colour of its own,
    // neither player's.
    public static final Color TURN_GREEN = new Color(58, 224, 97);

    // FlatLaf, dark, with the colours above for what it draws itself: the
    // accent (underline of the selected tab, focus rings) instead of its
    // blue, the selected rows, the headers of the tables and the scroll bars.
    public static void installLookAndFeel()
    {
        FlatLaf.setGlobalExtraDefaults(Map.of("@accentColor", String.format("#%06x", ACCENT.getRGB() & 0xFFFFFF)));
        FlatDarkLaf.setup();
        JFrame.setDefaultLookAndFeelDecorated(true);

        // The same whether the table has the focus or not.
        UIManager.put("Table.selectionBackground", ACCENT_SECONDARY);
        UIManager.put("Table.selectionForeground", Color.WHITE);
        UIManager.put("Table.selectionInactiveBackground", ACCENT_SECONDARY);
        UIManager.put("Table.selectionInactiveForeground", Color.WHITE);
        UIManager.put("TextField.selectionForeground", BACKGROUND_BLACK); // on the accent
        UIManager.put("Button.toolbar.hoverBackground", ACCENT_SECONDARY); // createStyledButton
        UIManager.put("Button.toolbar.pressedBackground", ACCENT_SECONDARY.darker());
        UIManager.put("TableHeader.background", ACCENT_SECONDARY);
        UIManager.put("TableHeader.hoverBackground", ACCENT_SECONDARY); // the headers do nothing on a click
        UIManager.put("TableHeader.pressedBackground", ACCENT_SECONDARY);
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

    // The bell of the join requests (GamePanel).
    public static Icon bellIcon(Color color)
    {
        return FontIcon.of(FontAwesomeSolid.BELL, 20, color);
    }

    // The colour of the discs of player 1 or 2.
    public static Color playerColor(int player)
    {
        return (player == 1) ? PLAYER1_RED : PLAYER2_YELLOW;
    }

    public static Color withAlpha(Color color, int alpha)
    {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    public static JButton createStyledButton(String text)
    {
        JButton button = new JButton(text);
        button.setFont(button.getFont().deriveFont(Font.BOLD, 18f));
        button.setForeground(Color.WHITE);

        // No box around it and no focus ring: FlatLaf paints only a
        // background under the mouse or the click. The focus still moves
        // with Tab, but a mouse click does not take it.
        button.putClientProperty("JButton.buttonType", "borderless");
        button.setFocusPainted(false);
        button.setRequestFocusEnabled(false);
        button.setMargin(new Insets(10, 15, 15, 15));

        button.setCursor(new Cursor(Cursor.HAND_CURSOR));

        return button;
    }

    public static JLabel createStyledLabel(String text)
    {
        JLabel label = new JLabel(text, SwingConstants.CENTER);

        label.setForeground(Color.WHITE);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 20f));

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
        private final String emptyText;
        private final String[] columnNames;

        // 'emptyText' is written in the middle when there are no rows.
        public TransparentTable(String emptyText, String... columnNames)
        {
            super(new Object[0][columnNames.length], columnNames);
            this.emptyText = emptyText;
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

            // Every entry centred, under its header (which FlatLaf centres).
            DefaultTableCellRenderer cells = new DefaultTableCellRenderer();
            cells.setHorizontalAlignment(SwingConstants.CENTER);
            setDefaultRenderer(Object.class, cells);
        }

        @Override
        protected void paintComponent(Graphics g)
        {
            super.paintComponent(g);
            if(getRowCount() == 0)
            {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2d.setColor(UIManager.getColor("Label.disabledForeground"));
                g2d.setFont(getFont().deriveFont(16f));
                int width = g2d.getFontMetrics().stringWidth(emptyText);
                g2d.drawString(emptyText, (getWidth() - width) / 2, getHeight() / 2);
            }
        }

        // 'action' on a double click on a row, which the first click selected.
        public void onDoubleClick(Runnable action)
        {
            addMouseListener(new MouseAdapter()
            {
                @Override
                public void mouseClicked(MouseEvent e)
                {
                    if(e.getClickCount() == 2 && rowAtPoint(e.getPoint()) != -1)
                    {
                        action.run();
                    }
                }
            });
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